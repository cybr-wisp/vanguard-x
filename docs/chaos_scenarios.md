# Chaos Engineering Scenarios

<!-- TODO (Week 3, Day 20-21): Document each scenario after implementation -->

## Scenario 1: Kill Processing Node

**Trigger:** Chaos panel "Kill Processing Node" button
**Behind the scenes:** `docker stop` on a pipeline container
**Expected behavior:** Kafka rebalances consumers, surviving nodes pick up partitions,
throughput dips briefly then recovers
**Recovery time target:** < 10 seconds

## Scenario 2: Inject Network Latency

**Trigger:** Chaos panel "Inject Latency" toggle
**Behind the scenes:** `tc qdisc` adds artificial delay on the container network
**Expected behavior:** p99 latency spikes, p50 remains stable, no data loss
**Recovery:** Remove `tc` rule, latency returns to baseline

## Scenario 3: Spike Track Volume

**Trigger:** Chaos panel "10x Volume" button
**Behind the scenes:** Simulator increases output rate by 10x
**Expected behavior:** Pipeline handles burst or degrades gracefully (no crash),
Kafka consumer lag increases temporarily
**Recovery:** Volume returns to normal, lag drains

## Metrics to Capture During Each Scenario

- Ingestion throughput (pkt/sec)
- End-to-end latency (p50/p95/p99)
- Kafka consumer lag
- Active track count
- Alert rate
- Screenshot/recording of the dashboard during the event
