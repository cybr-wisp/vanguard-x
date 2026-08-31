# Performance Benchmark Methodology

## Principles

1. **Warm-up before measurement.** JIT compilation stabilizes after ~5000
   iterations. All benchmarks discard the first N seconds of data.

2. **Repeatability.** Each scenario runs with a deterministic seed and
   fixed report rate. Results are averaged over 3 runs minimum.

3. **Percentile latency, not averages.** p50, p95, and p99 are reported.
   Averages hide tail latency that matters for real-time systems.

4. **Push to saturation.** The benchmark matrix increases load until the
   system can no longer meet its latency target. The breaking point and
   bottleneck hypothesis are documented.

5. **Coordinated omission awareness.** The load generator uses a fixed
   send schedule (not closed-loop). If the system falls behind, the next
   send does not wait; this avoids understating tail latency.

## Benchmark matrix

| Scenario | Targets | Report rate | What to record |
|----------|---------|-------------|----------------|
| Baseline | 100     | 1k/s        | throughput, p50/p95/p99, CPU, memory |
| Moderate | 500     | 5k/s        | same + queue depth + Kafka lag |
| Target   | 1,000   | 10k/s       | same + packet drops + UI stability |
| Stress   | 2,000+  | 20k/s+      | find saturation point |
| Recovery | target  | target load  | lag spike, recovery time, drops |
| Loss     | target  | target load  | tracking RMSE and lifecycle impact |

## Hardware

Document the exact machine used:
- CPU model and core count
- RAM
- Disk type (SSD/NVMe)
- OS and kernel version
- JVM version and flags
- Docker resource limits (if any)

## What to measure

Per pipeline stage:
- Throughput (accepted reports/sec)
- Latency (p50, p95, p99)
- Queue depth
- CPU and memory utilization

End-to-end:
- Report ingestion to fused track publication latency
- Kafka consumer lag (backlog indicator)
- Packet drop rate under load
- Track count stability

## Honesty rule

If the target is 10k reports/sec and the machine sustains 7.8k/sec at
the chosen latency objective, report 7.8k/sec and explain the bottleneck.
Measured limitations are stronger than decorative performance claims.
