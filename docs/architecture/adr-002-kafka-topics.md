# ADR-002: Kafka Topic and Keying Design

## Status

Accepted

## Context

Vanguard uses Kafka as a replayable boundary between major pipeline stages.

Topic structure and record keys affect:

- ordering scope
- partition placement
- consumer-group parallelism
- failure recovery
- replay behavior
- duplicate handling
- retention behavior

Kafka guarantees ordering only within a partition, so keys are selected according to the smallest entity for which ordering matters.

## Decision

### Topic contracts

| Topic | Purpose | Configured partitions | Record key |
|---|---|---:|---|
| `sensor-reports.raw` | Validated sensor reports | 3 | `sensor_id` |
| `tracks.fused` | Canonical fused-track updates | 6 | `track_id` |
| `track-events` | Stateful geofence transitions | 3 | `trackId:zoneId` |
| `system-events` | Low-volume component/lifecycle events | 1 | `component_id` |

These partition counts are the Vanguard topic-design values.

Deployments must provision topics with the intended partition counts. Topic auto-creation must not be treated as proof that those counts were applied.

---

## Keying rationale

### `sensor-reports.raw`

Records are keyed by `sensor_id`.

This ensures that all reports carrying the same sensor ID are routed to the same partition and therefore retain Kafka's per-partition ordering.

With three configured partitions, reports from different sensors can be distributed across partitions, but Vanguard does **not** assume that three sensor keys necessarily map one-to-one onto three partitions.

Kafka's key partitioning is hash-based, so distinct keys may map to the same partition.

The ordering guarantee Vanguard relies on is:

> Reports for one sensor remain ordered relative to other reports carrying that same key.

The UDP gateway suppresses duplicate sensor sequence numbers before records enter Kafka. This is ingestion-boundary deduplication only; it is not an end-to-end exactly-once guarantee.

### `tracks.fused`

Fused-track updates are keyed by `track_id`.

All updates for the same canonical track therefore remain on the same partition while the topic's partition count is unchanged.

This allows downstream consumers to process different tracks concurrently while preserving the update order of an individual track.

The six configured partitions provide bounded parallelism for consumers of fused-track state.

### `track-events`

Spatial transition events use the composite key:

```text
trackId:zoneId
```

The required ordering scope is one track/zone pair.

For a correctly keyed pair, transitions such as:

```text
CLEAR -> ADVISORY -> WARNING -> BREACH
```

and the corresponding exit transitions remain ordered within one Kafka partition.

The spatial state machine suppresses repeated classifications that do not represent an actual state transition.

### `system-events`

`system-events` is reserved for low-volume component and lifecycle events keyed by `component_id`.

The reference design uses one partition because expected volume is low and a single partition provides a simple total order across system events.

Preserving that global ordering is currently more valuable than additional consumer parallelism.

If event volume later grows enough to require multiple partitions, the ordering contract must be revisited explicitly.

---

## Consumer groups

Each independently scalable Kafka processing stage uses its own consumer group.

The tracking stage consumes `sensor-reports.raw` as one logical consumer group.

The spatial stage independently consumes `tracks.fused` using its own consumer group.

This separation allows:

- tracking and spatial processing to scale independently
- Kafka to rebalance partitions only among replicas of the same stage
- downstream stages to consume the same upstream stream without sharing offsets
- failure in one consumer group to avoid altering the committed position of another

Partition count places an upper bound on useful parallelism within a consumer group.

For example, the six partitions on `tracks.fused` allow at most six simultaneously assigned consumers in a spatial-processing group before additional consumers become idle.

---

## Producer semantics

The current Kafka producers use:

```text
acks=1
```

A successful acknowledgement therefore means the partition leader accepted the record.

This is an explicit reference-deployment tradeoff: it avoids the additional acknowledgement latency associated with stronger replication guarantees.

The current local deployment uses replication factor 1, so changing to `acks=all` alone would not provide multi-broker durability without first changing the broker topology and replication configuration.

---

## Consumer offset semantics

The current tracking adapter uses Kafka consumer groups with automatic offset commits.

Its processing loop is conceptually:

```text
poll
  -> process reports
  -> publish fused-track records
  -> next poll
```

Because offset management and downstream publication are not transactional, Vanguard does **not** claim end-to-end exactly-once semantics.

A failure can occur at boundaries between:

- input consumption
- processing
- offset commit
- output publication

Where duplicate input is possible, downstream state machines and canonical track identity should tolerate or suppress duplicate effects where that behavior is explicitly implemented and verified.

A stronger delivery contract would require deliberate offset management and/or Kafka transactions rather than documentation alone.

---

---

## Retention and compaction

The current reference deployment does not configure a custom Kafka retention or log-compaction policy for these topics. Broker defaults therefore apply.

`tracks.fused` is a natural candidate for log compaction in a longer-lived deployment because records are keyed by `track_id` and consumers may care about the latest known state for each track.

However, compaction is **not enabled or relied upon by the current implementation**.

Historical replay must therefore not assume that only the latest record for each track remains.

A production deployment should choose topic policy deliberately:

| Topic | Candidate policy | Rationale |
|---|---|---|
| `sensor-reports.raw` | Time/size retention | Preserve raw replay and debugging history |
| `tracks.fused` | Potentially `compact,delete` | Retain latest track state while bounding historical growth |
| `track-events` | Time/size retention | Preserve transition history |
| `system-events` | Time/size retention | Preserve system-event history unless another audit store is used |

Any retention or compaction change must preserve the replay, recovery, and forensic requirements of the deployment.

---

## Replay

`sensor-reports.raw` serves as the replayable input boundary for the tracking pipeline for as long as the required offsets remain within Kafka's configured retention window.

Replay consumers should use:

- an isolated consumer-group ID
- an explicitly selected starting offset
- configuration that does not interfere with live-consumer offsets

Deterministic estimator replay is verified separately by `ReplayIT`, which confirms identical estimator state and covariance for identical seeded measurements.

Kafka offset recovery is verified separately by `KafkaRecoveryIT`, which demonstrates that a restarted consumer resumes after an explicitly committed offset.

---

## Ordering guarantees

The architecture relies only on Kafka guarantees that are actually provided.

| Entity | Ordering scope |
|---|---|
| Sensor report | Same `sensor_id` key within one partition |
| Fused-track update | Same `track_id` key within one partition |
| Spatial event | Same `trackId:zoneId` key within one partition |
| System event | Single `system-events` partition |

Ordering between different partitions is intentionally not assumed.

---

## Partition-count implications

Partition counts are part of the architecture rather than arbitrary deployment numbers.

They determine:

- maximum useful consumer parallelism
- rebalance granularity
- key distribution
- ordering boundaries
- operational migration cost

Increasing a partition count can change key-to-partition mapping.

For keyed topics, partition-count changes must therefore be reviewed carefully because records for the same logical entity written before and after a repartitioning change may not share the same historical partition.

Partition counts should not be changed solely to increase apparent parallelism.

---

## Consequences

- ordering is guaranteed only within each documented Kafka key/partition scope
- tracking and spatial processing use independent consumer groups and can scale independently
- partition counts bound useful consumer parallelism and are therefore architecture decisions
- `sensor-reports.raw` remains replayable only while required offsets remain within the configured retention window
- the reference deployment does not claim transactional or end-to-end exactly-once processing
- retention and compaction are explicit deployment policies; `tracks.fused` is a candidate for `compact,delete` but does not currently rely on compaction
- changes to keys, partition counts, retention, or offset semantics require review of ordering, replay, and recovery assumptions
