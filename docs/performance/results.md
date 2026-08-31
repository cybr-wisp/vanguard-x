# Performance Results

## Hardware

- CPU: [FILL IN]
- RAM: [FILL IN]
- Disk: [FILL IN]
- OS: [FILL IN]
- JVM: OpenJDK 21, default flags
- Docker: [resource limits if any]

## Summary

| Scenario | Rate | Throughput | p50 | p95 | p99 | Drops | Queue | CPU |
|----------|------|-----------|-----|-----|-----|-------|-------|-----|
| Baseline | 1k/s | [M] | [M] | [M] | [M] | [M] | [M] | [M] |
| Moderate | 5k/s | [M] | [M] | [M] | [M] | [M] | [M] | [M] |
| Target | 10k/s | [M] | [M] | [M] | [M] | [M] | [M] | [M] |
| Stress | 20k/s | [M] | [M] | [M] | [M] | [M] | [M] | [M] |

[M] = fill with measured values only.

## Saturation point

The system stops meeting the latency target (p99 < [X]ms) at
approximately [MEASURED] reports/sec.

## Bottleneck hypothesis

[FILL IN after measurement. Example: "The bottleneck is CPU-bound
EKF matrix inversion in the tracking processor. At 8.2k reports/sec,
all 4 worker threads are saturated and queue depth begins growing."]

## Recovery behavior

| Metric | Steady state | During failure | Recovery |
|--------|-------------|----------------|----------|
| Consumer lag | [M] | [M] | [M] |
| p99 latency | [M] | N/A | [M] (spike) |
| Drops | [M] | [M] | [M] |

## Raw data

Raw CSV results are in `benchmarks/results/`.

## Charts

- `latency-throughput.png`: latency vs. report rate
- `executor-comparison.png`: fixed pool vs. virtual threads
