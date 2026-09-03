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

## Processing Latency

### 200 Targets — Spatial Index Enabled

Three-run median:

| Percentile | Latency |
|---|---:|
| p50 | **16.74 ms** |
| p95 | **23.92 ms** |
| p99 | **37.72 ms** |

The spatial-index-enabled configuration is treated as the primary operational
benchmark configuration.

### 200 Targets — Without Spatial Index

Three-run median:

| Percentile | Latency |
|---|---:|
| p50 | **16.40 ms** |
| p95 | **26.12 ms** |
| p99 | **38.37 ms** |

The benchmark does not show a large or uniform latency advantage from enabling
the spatial index. Spatial indexing should therefore not be described as a
general latency optimization based on these measurements.

---

## Throughput Scaling

### Spatial Index Enabled

| Targets | Throughput |
|---:|---:|
| 50 | **35,728 reports/s** |
| 200 | **18,546 reports/s** |
| 500 | **12,485 reports/s** |
| 1,000 | **9,771 reports/s** |

### Without Spatial Index

| Targets | Throughput |
|---:|---:|
| 50 | **24,400 reports/s** |
| 200 | **16,402 reports/s** |
| 500 | **12,132 reports/s** |
| 1,000 | **11,280 reports/s** |

Spatial indexing improves throughput at several lower target counts in this
benchmark, but the 1,000-target result regresses relative to the non-indexed
configuration. No blanket claim that spatial indexing improves throughput at
all scales is made.

---

## Deterministic Replay

Repeated execution of the same replay workload produced identical tracking
accuracy:

| Metric | Result |
|---|---:|
| Replay max RMSE delta | **0** |
| Deterministic result | **Yes** |

This provides a reproducible basis for regression testing and performance
experimentation.

---

## Event Deduplication

The alert state machine was stress-tested using repeated identical BREACH
inputs.

**1,000 repeated BREACH inputs produced exactly 1 emitted event.**

An exit followed by a later re-entry correctly produced a new zone-entry
event.

---

## Packet-Loss Testing

The benchmark also evaluates tracking under simulated packet loss at:

- 0%
- 5%
- 10%
- 20%

Association accuracy remained 100% in the recorded benchmark runs.

The packet-loss RMSE values are intentionally not used as headline performance
claims because the observed RMSE decreases under several packet-loss
conditions. This counterintuitive behavior requires further analysis of the
evaluation methodology before those RMSE results are interpreted as tracking
improvements.

The benchmark also recorded concurrent duplicate-track behavior that warrants
additional investigation before being promoted as a headline metric.

---

## Covariance and Reacquisition

The benchmark validates that estimated uncertainty responds to measurement
updates, coasting, and reacquisition.

Observed behavior includes:

- covariance reduction following measurement updates;
- covariance growth while a track is coasting without observations;
- substantial covariance reduction following reacquisition.

These tests are retained primarily as estimator-correctness evidence rather
than headline performance metrics.

---
