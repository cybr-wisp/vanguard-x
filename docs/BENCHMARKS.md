# VANGUARD-X Performance & Correctness Benchmarks

## Benchmark Summary

VANGUARD-X was evaluated using a reproducible JDK 21 benchmark suite covering
multi-target tracking accuracy, sensor fusion, association correctness,
throughput, latency, packet-loss behavior, deterministic replay, covariance
behavior, and event deduplication.

All headline performance values below are taken from the frozen three-run
baseline using median results rather than selecting the best run.

### Environment

- JDK: Eclipse Temurin 21.0.12.1
- CPU cores: 8
- Runs: 3
- Docker/backend/frontend: stopped during in-process benchmarking
- Workload: synthetic multi-sensor tracking simulation
- Benchmark baseline: `8795e8c`
- Benchmark class: `FullBenchmark`

> Latency values reported here measure in-process tracking workload execution.
> They are not full end-to-end sensor-to-browser latency measurements.

---

## Headline Results

| Metric | Result |
|---|---:|
| Association accuracy | **100.0%** |
| False tracks | **0** |
| Position RMSE | **10.86 m** |
| Velocity RMSE | **4.17 m/s** |
| Sensor-fusion improvement | **63.0%** |
| 200-target indexed throughput | **18,546 reports/s** |
| 200-target indexed p50 latency | **16.74 ms** |
| 200-target indexed p95 latency | **23.92 ms** |
| 200-target indexed p99 latency | **37.72 ms** |
| Replay RMSE delta | **0** |
| Repeated BREACH inputs | **1,000 -> 1 emitted event** |

---

## Tracking Accuracy

The tracking benchmark evaluates estimated tracks against simulator ground
truth.

| Metric | Result |
|---|---:|
| Position RMSE | **10.86 m** |
| Velocity RMSE | **4.17 m/s** |
| Association accuracy | **100.0%** |
| False tracks | **0** |

These measurements are produced under the benchmark's synthetic sensor,
motion, and noise model and should be interpreted within that workload rather
than as real-world radar performance claims.

---

## Sensor Fusion

VANGUARD-X combines noisy sensor observations through its tracking and
estimation pipeline.

| Metric | Result |
|---|---:|
| Raw observation RMSE | **29.24 m** |
| Fused RMSE | **10.82 m** |
| RMSE improvement | **63.0%** |

The benchmark therefore observed a 63% reduction in positional RMSE relative
to the raw sensor measurements under the tested simulation.

---
