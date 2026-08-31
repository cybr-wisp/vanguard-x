# ADR-002: Kafka Topic and Keying Design

## Status
Accepted

## Context
Vanguard uses Kafka to decouple pipeline stages and enable replay.
The topic structure and keying strategy affect ordering guarantees,
partition balance, consumer group behavior, and replay correctness.

## Decision

### Topics

| Topic              | Purpose                          | Partitions | Key           |
|--------------------|----------------------------------|------------|---------------|
| sensor-reports.raw | Validated sensor reports         | 3          | sensor_id     |
| tracks.fused       | EKF-updated canonical tracks     | 6          | track_id      |
| track-events       | Zone transition events           | 3          | trackId:zoneId|
| system-events      | Health/lifecycle system events    | 1          | component_id  |

### Keying rationale

**sensor-reports.raw keyed by sensor_id:** All reports from one sensor
land on the same partition, preserving per-sensor sequence ordering.
With 3 sensors and 3 partitions, each sensor gets its own partition.

**tracks.fused keyed by track_id:** All updates for one track land on
the same partition, so downstream consumers see track state changes in
order. 6 partitions allow parallel consumption while preserving per-track
ordering.

**track-events keyed by trackId:zoneId:** All events for one track/zone
pair are ordered. This ensures ZONE_ENTRY always precedes ZONE_EXIT for
the same pair.

**system-events keyed by component_id:** Single partition is sufficient
for low-volume system events. Ordering is per-component.

### Delivery semantics

- Producer: acks=1 (leader acknowledgment). Provides durability without
  the latency of acks=all for a portfolio project.
- Consumer: auto-commit with at-least-once semantics. The SequenceTracker
  provides idempotency at the gateway level. Downstream consumers handle
  duplicates via the track lifecycle state machine.

### Replay

Replay reads from offset 0 on sensor-reports.raw and processes in
event-time order. Consumer group IDs for replay use a unique suffix
to avoid interfering with live consumers.

## Consequences

- Per-sensor ordering is guaranteed within a partition
- Per-track ordering is guaranteed for fused tracks and events
- Consumer group rebalancing provides fault tolerance
- Replay is possible from any offset without special tooling
