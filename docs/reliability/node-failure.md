# Node Failure and Recovery

## Scenario

Kill the tracking processor while upstream report production continues,
then restart the processor and observe Kafka backlog and tracker recovery.

## Procedure

1. Start the full reference stack.
2. Confirm steady-state processing and near-zero consumer lag.
3. Stop the tracking processor.
4. Continue producing reports during the outage.
5. Observe Kafka consumer lag while processing is unavailable.
6. Restart the tracking processor.
7. Observe partition assignment, offset recovery, backlog drain, and track behavior.
8. Preserve logs and metrics as a committed benchmark artifact.

## Expected behavior

### During failure

- `sensor-reports.raw` continues retaining reports within its configured
  retention window.
- tracking output to `tracks.fused` stops.
- tracking-consumer lag grows while reports continue arriving.
- previously published state may remain visible until its own state-retention
  or expiry behavior removes it.

### During recovery

- the tracking consumer rejoins its Kafka consumer group
- partitions are assigned to an available consumer
- processing resumes from the applicable committed offsets
- accumulated backlog begins draining
- temporary latency elevation may occur while backlog is processed
- lifecycle outcomes depend on how much observation time was missed

## Measurement status

A controlled full-stack container-failure campaign with committed raw metrics
has not yet been completed.

Therefore Vanguard currently makes no numerical claim for:

- maximum consumer lag during a processor outage
- Kafka rebalance duration
- backlog drain time
- recovery p99 latency
- number of tracks entering `COASTING`
- number of tracks dropped or reacquired after restart

These values will only be documented after a reproducible fault-injection
campaign records them.

## Evidence boundary

Kafka replay and offset-recovery tests can verify narrower transport
properties, but they are not equivalent to a full-stack processor-failure
experiment.

The expected behavior above is therefore a design expectation rather than a
measured availability claim.