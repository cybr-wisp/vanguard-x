# Vanguard Interview Talking Points

## System design

**Why UDP instead of TCP for this telemetry path?**
Sensor reports are ephemeral: a lost report from 500ms ago is useless because the target has moved. TCP's retransmission and head-of-line blocking would delay current reports to deliver stale ones. UDP lets the tracker work with whatever arrives, and the track lifecycle explicitly handles missing observations through the COASTING state.

**What does Netty guarantee, and what must you never do on the event loop?**
Netty guarantees that a single channel's events are processed by one thread (the event loop), eliminating the need for locks on channel state. You must never do blocking I/O, heavy computation, or synchronized waits on the event loop -- that blocks all other channels sharing it. In Vanguard, the handler decodes Protobuf, validates fields, and offers to a bounded queue. The EKF work runs on a separate worker pool.

## Estimation

**Why is range/bearing nonlinear and why does that justify an EKF?**
The measurement function h(x) contains sqrt (for range) and atan2 (for bearing). A standard Kalman filter requires linear observation models. The EKF linearizes h(x) via the Jacobian evaluated at the current predicted state, making it a real engineering requirement rather than a buzzword. If the simulator emitted Cartesian (x,y) positions, a standard KF would suffice and the EKF would be unjustified.

**What does covariance mean and how does it change during coasting?**
Covariance is not just "accuracy" -- it is the geometric shape and scale of uncertainty. During coasting (no observations), each predict step adds process noise Q to the covariance, so it grows. This is correct: the filter honestly reports that it knows less about a track it hasn't observed recently. The growing covariance ellipse is visible in the UI.

**Why use Mahalanobis distance instead of Euclidean distance for association?**
Euclidean distance treats all directions equally and ignores uncertainty. A 200m residual should be rejected for a precise sensor but might be plausible for a noisy one. Mahalanobis distance normalizes by the innovation covariance S (which includes both state uncertainty and sensor noise), so the gate automatically adapts to each sensor's quality and each track's confidence level.

## Distributed systems

**How do you handle duplicate and out-of-order packets?**
The SequenceTracker maintains the highest observed sequence number per sensor. Reports with sequence <= highest are rejected as duplicates. Reports with sequence > highest + 1 are flagged as gaps but accepted. The tracking pipeline processes reports in event-time order within a bounded reordering window.

**What does Kafka guarantee about ordering?**
Kafka guarantees ordering within a partition, not across partitions. Vanguard keys sensor-reports.raw by sensor_id and tracks.fused by track_id, so all reports from one sensor (or updates for one track) are ordered. Cross-sensor ordering is handled by event-time processing in the tracker.

**What happens when a consumer dies?**
The consumer group detects the missing heartbeat after session.timeout.ms. Kafka triggers a partition rebalance, reassigning the dead consumer's partitions to surviving instances. The new owner resumes from the last committed offset. Consumer lag spikes during the gap and drains once processing resumes. In Vanguard, tracks may coast during the outage but reacquire after recovery.

## Performance

**Why did the fixed worker pool or virtual-thread configuration win?**
[Fill in after ADR-003 is completed with measured results.] The key insight is that the tracking pipeline's hot path is CPU-bound (matrix multiplication, distance computation), not I/O-bound. Virtual threads provide no inherent speedup for CPU-bound work -- their advantage is in reducing the cost of blocking I/O.

**Where does the system saturate and what metric tells you first?**
[Fill in after the performance campaign.] Typically queue depth is the leading indicator: it grows before packets are dropped and before latency degrades significantly.

**How do you know the filter improved tracking rather than merely smoothing the path?**
By comparing EKF position RMSE against raw single-sensor observation RMSE on the same ground-truth trajectory. If the filter is working correctly, its RMSE is lower because it combines information from multiple sensors and uses the motion model to predict through gaps. If the filter were just smoothing, it would add lag without reducing error.

## What would you redesign for production?

- TLS everywhere (DTLS for UDP, Kafka SSL, Redis TLS)
- IMM (interacting multiple model) for maneuvering targets
- JPDA or MHT for dense multi-target scenarios
- Horizontal scaling with Kafka partitioning
- Historical storage (TimescaleDB or similar) for post-mission analysis
- Authentication and authorization on the API
