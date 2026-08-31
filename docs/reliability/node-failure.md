# Node Failure and Recovery

## Scenario

Kill the tracking processor container while the simulator continues
sending reports at the target load rate.

## Procedure

1. Start the full stack via `docker compose up`
2. Confirm steady-state: tracks confirmed, throughput stable, lag near zero
3. `docker compose kill vanguard-tracking` (or `docker stop`)
4. Observe metrics for 30 seconds
5. `docker compose start vanguard-tracking`
6. Observe recovery

## Expected behavior

### During failure (steps 3-4)

- Kafka consumer lag on sensor-reports.raw increases linearly
  (reports accumulate but no consumer is processing them)
- No fused tracks published to tracks.fused
- Active tracks in Redis continue showing their last known state
  (TTLs prevent stale data from persisting indefinitely)
- The UI shows tracks freezing in place (no WebSocket updates)

### During recovery (step 5)

- The restarted consumer joins the consumer group
- Kafka triggers a partition rebalance
- The consumer resumes from the last committed offset
- Backlog is processed: consumer lag decreases toward zero
- Processing latency may spike temporarily as the backlog drains
- Tracks that were coasting during the outage either:
  - Reacquire (if the outage was shorter than missesToDrop cycles)
  - Drop (if too many cycles passed)

## Metrics evidence

| Metric | Steady state | During failure | Recovery |
|--------|-------------|----------------|----------|
| Consumer lag | ~0 | [MEASURED] | returns to ~0 |
| Processing latency p99 | [MEASURED] | N/A | spike then baseline |
| Active tracks | [MEASURED] | frozen | [MEASURED] |
| Coasting tracks | 0 | N/A | [MEASURED] peak |

## Recovery time

Kafka consumer group rebalance typically completes in 5-15 seconds
(configurable via `session.timeout.ms` and `heartbeat.interval.ms`).
Backlog drain time depends on accumulated lag and processing throughput.
