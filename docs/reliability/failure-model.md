# Failure Model

## Design philosophy

Vanguard is designed for graceful degradation, not zero-failure operation.
Every failure mode has at least one metric that makes it observable and a
documented recovery path.

## Failure scenarios tested

### 1. Processor failure (node-failure.md)

**Trigger:** Kill the tracking processor container while load continues.

**Observable:** Kafka consumer lag spikes immediately. The partition
previously owned by the dead consumer is reassigned to a surviving
instance (or the restarted instance). During reassignment, no fused
tracks are published for the affected partitions.

**Recovery:** After rebalance completes, the new consumer resumes from
the last committed offset. Tracks that were coasting during the outage
may have accumulated enough misses to drop, but confirmed tracks with
sufficient history survive.

**Metrics to watch:** `vanguard.pipeline.kafka.consumer.lag`,
`vanguard.tracking.tracks.coasting`, processing latency spike during
rebalance.

### 2. Packet loss (packet-loss.md)

**Trigger:** Inject 5% synthetic loss via the NetworkImpairmentModel
or the ChaosPanel.

**Observable:** Confirmed tracks enter COASTING rather than disappearing
immediately. Covariance grows. When observations resume, tracks reacquire.

**Metrics to watch:** `vanguard.gateway.packets.dropped`,
`vanguard.tracking.tracks.coasting`, position RMSE increases during loss.

### 3. Jitter and reordering

**Trigger:** Inject ~200ms jitter via the simulator or ChaosPanel.

**Observable:** The tracking pipeline processes reports in event-time
order. Late arrivals beyond the bounded reordering window are dropped.
Throughput may decrease slightly due to ordering overhead.

**Metrics to watch:** processing latency p99, queue depth.

### 4. Traffic burst

**Trigger:** Spike report rate by 10x for 10 seconds.

**Observable:** Queue depth increases, p99 latency spikes, packets may
be dropped if the queue fills. After the spike, the queue drains and
latency returns to baseline.

**Metrics to watch:** `vanguard.pipeline.queue.depth`, p99 latency,
`vanguard.gateway.packets.dropped`.

### 5. Sensor quality degradation

**Trigger:** Increase one sensor's noise covariance by 10x.

**Observable:** The EKF trusts the degraded sensor less (its larger R
reduces its Kalman gain). Fused uncertainty increases slightly but the
estimate remains stable because the other sensors compensate.

**Metrics to watch:** per-sensor association rate, fused position RMSE.

## Key invariant

No single failure should cause silent data corruption. Every degradation
is visible in at least one metric before it affects the operational picture.
