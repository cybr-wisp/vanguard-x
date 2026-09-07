# v a n g u a r d - x 

<!--
HERO DEMO

Record the final 12–15 second demo and save it as:

docs/figures/demo.gif

Then replace this comment with:

![Vanguard-X live multi-sensor tracking demo](docs/figures/demo.gif)
-->

**Three imperfect sensors observe the same airspace and disagree. Vanguard turns asynchronous, noisy range/bearing reports into a single fused track picture, raises stateful geofence events, and preserves track identity through missed observations and replayable infrastructure boundaries.**

[![CI](https://github.com/cybr-wisp/vanguard-x/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/cybr-wisp/vanguard-x/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.7-231F20?logo=apachekafka&logoColor=white)
![Estimator](https://img.shields.io/badge/Estimator-EKF-0F766E)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Vanguard-X is a Java 21 multi-sensor tracking and state-estimation system built around UDP telemetry, Protobuf contracts, Kafka event streams, an Extended Kalman Filter, Redis live state, stateful geofencing, and a React/MapLibre operational UI.

> **Scope:** Vanguard-X is an educational, simulated, unclassified software project. Sensors, trajectories, measurements, and geofences are synthetic. It is not an operational combat, targeting, or weapon-control system.

---

## Contents

- [Measured results](#measured-results)
- [Architecture](#architecture)
- [How sensor fusion works](#how-sensor-fusion-works)
- [Track lifecycle and uncertainty](#track-lifecycle-and-uncertainty)
- [Failure modes and recovery evidence](#failure-modes-and-recovery-evidence)
- [Requirements traceability](#requirements-traceability)
- [Sensor interface](#sensor-interface)
- [Module map](#module-map)
- [Benchmark methodology](#benchmark-methodology)
- [Verification and CI](#verification-and-ci)
- [Observability](#observability)
- [Documentation](#documentation)
- [Known limitations](#known-limitations)
- [Quick start](#quick-start)
- [License](#license)

---

## Measured results

All values below are measured results from the frozen benchmark campaign **(fixed workload, fixed configuration, and committed raw outputs)**, not design targets.

| Category | Metric | Result |
|---|---|---:|
| Tracking | Position RMSE | **10.86 m** |
| Tracking | Velocity RMSE | **4.17 m/s** |
| Tracking | Association accuracy | **100.0%** |
| Tracking | False tracks | **0** |
| Fusion | Raw observation RMSE | **29.24 m** |
| Fusion | Fused position RMSE | **10.82 m** |
| Fusion | RMSE reduction | **63.0%** |
| Throughput | 50 concurrent targets | **48,858 reports/s** |
| Throughput | 200 concurrent targets | **21,348 reports/s** |
| Throughput | 500 concurrent targets | **14,962 reports/s** |
| Throughput | 1,000 concurrent targets | **16,696 reports/s** |
| Latency | p50 at 200 targets | **13.49 ms** |
| Latency | p95 at 200 targets | **18.45 ms** |
| Latency | p99 at 200 targets | **20.73 ms** |
| Determinism | Replay max RMSE delta | **0** |
| Eventing | 1,000 repeated BREACH inputs | **1 emitted event** |

**Benchmark environment:** Eclipse Temurin JDK 21.0.12.1, 8-core host, three runs per measured point, median reported. The primary configuration uses the 300 m operational spatial grid. Backend, frontend, and Docker services were stopped for the in-process tracking benchmark.

> **Latency scope:** the 13.49 / 18.45 / 20.73 ms figures measure **in-process tracking workload latency**, not full UDP-to-browser end-to-end latency.

Raw benchmark outputs are committed under [`benchmarks/results/optimized-three-run/`](benchmarks/results/optimized-three-run/).

Full methodology, ablations, and limitations are documented in [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

---

## Architecture

Vanguard is organized around explicit transport, tracking, state, spatial-event, and presentation boundaries.

Kafka provides replayable stream boundaries between ingestion and processing. Redis holds current track state for low-latency access. The Spring Boot API exposes live track, event, and health streams to the frontend without embedding tracking logic in the presentation layer.

<!--
ARCHITECTURE DIAGRAM

Save the final architecture image as:

docs/figures/architecture.png

Then uncomment:

![Vanguard-X architecture](docs/figures/architecture.png)
-->

![Vanguard-X architecture](docs/architecture/architecture_diagram.png)

The logical processing pipeline is:

```text
World Simulator
    |
    v
Synthetic Sensors A / B / C
(noise + bias + asynchronous reports)
    |
    v
Network Impairments
(loss + jitter + reordering)
    |
    v
UDP + Protobuf
    |
    v
Netty UDP Gateway
(validation -> sequencing -> deduplication)
    |
    v
Kafka: sensor-reports.raw
    |
    v
Tracking Processor
(event-time ordering
 -> Mahalanobis association
 -> EKF
 -> track lifecycle)
    |
    +-----------> Kafka: tracks.fused
    |                    |
    |                    v
    |              Spatial Engine
    |            geofence state machine
    |                    |
    |                    v
    |             Kafka: track-events
    |                    |
    |                    |
    +-----------> Redis  |
                 live    |
                 state   |
                    \    /
                     \  /
                      vv
               Spring Boot API
               REST + WebSocket
                      |
                      v
                Vanguard UI
              React + MapLibre
```

Supporting infrastructure:

- Prometheus -> runtime metrics
- Grafana -> dashboards
- CodeQL -> static analysis
- CycloneDX -> SBOM generation

The reference Docker Compose deployment packages the backend runtime into `vanguard-api`, while the Maven modules preserve the internal subsystem boundaries.

---

## How sensor fusion works

The runtime tracker uses a 2D constant-velocity Extended Kalman Filter with state

```
x = [px, py, vx, vy]^T
```

Each sensor reports a nonlinear range/bearing observation from a known sensor position.

For each observation cycle, Vanguard:

1. **Predicts** alive tracks to the observation timestamp using the motion model.
2. **Gates and associates** observations against predicted track priors using Mahalanobis distance.
3. **Computes** the EKF innovation in range/bearing space, including bearing-residual normalization.
4. **Updates** matched track state and covariance using the Kalman gain.
5. **Advances** lifecycle state through `TENTATIVE -> CONFIRMED -> COASTING -> DROPPED`.

Covariance is updated using the Joseph stabilized form:

```
P = (I - KH) P (I - KH)^T + K R K^T
```

rather than the simplified `(I - KH)P` update. This reduces finite-precision loss of covariance symmetry and positive-semidefiniteness.

The estimator also exposes Normalized Innovation Squared (NIS) and Normalized Estimation Error Squared (NEES) for simulation-time consistency evaluation.

Ground truth is used only by the evaluation path, never by runtime association.

### What the benchmark observed

```
Raw sensor position RMSE
        29.24 m
           |
           |  multi-sensor estimation
           v
Fused position RMSE
        10.82 m

RMSE reduction: 63%
```

The measured 63% reduction is specific to Vanguard's synthetic sensor, motion, and noise model and is not presented as a real-world radar-performance claim.

---

## Track lifecycle and uncertainty

Tracking is explicitly stateful.

Default lifecycle thresholds are configurable, with the reference tracker using:

```
hitsToConfirm = 3
missesToCoast = 3
missesToDrop  = 8
```

The observation that creates a track counts as the first hit.

Multiple sensors reporting at the same timestamp may all improve the state estimate, but together they count as one lifecycle observation-cycle hit, not several.

```
                 3 consecutive observation-cycle hits
                       (default; configurable)
TENTATIVE --------------------------------------------> CONFIRMED
                                                           |
                                                           | 3 consecutive misses
                                                           v
                                                       COASTING
                                                           |
                                         +-----------------+-----------------+
                                         |                                   |
                                  valid observation                  misses continue
                                         |                                   |
                                         v                                   v
                                    CONFIRMED                            DROPPED
```

A COASTING track remains active. Its motion model predicts state forward while covariance grows to represent increasing uncertainty.

When a valid observation returns, the existing canonical track can reacquire without changing identity.

`PacketLossIT` verifies the sequence:

```
CONFIRMED
    |
three missed cycles
    v
COASTING
uncertainty increases
    |
valid observation
    v
CONFIRMED
same canonical track ID
```

---

## Failure modes and recovery evidence

Reliability claims are tied to executable evidence rather than inferred only from architecture.

| Condition | Verified behavior | Evidence |
|---|---|---|
| Kafka consumer restart | First consumer commits 5 of 20 records; restarted consumer receives exactly the remaining 15 and does not replay the committed 5 | `KafkaRecoveryIT` |
| Missed observations | Confirmed track enters COASTING after configured misses; covariance grows; next valid measurement returns it to CONFIRMED under the same canonical track ID | `PacketLossIT` |
| Recovered-state persistence | Reacquired track state round-trips through a real Redis container | `PacketLossIT` |
| Deterministic replay | Same seeded measurements produce bit-identical final estimator state and covariance; replay fingerprint also survives Kafka round-trip | `ReplayIT` |
| Pipeline transport | Raw records flow through the tracking Kafka adapter to `tracks.fused`; resulting state is persisted through Redis | `PipelineIT` |
| Repeated spatial breach | 1,000 repeated BREACH inputs produce exactly one emitted event | `FullBenchmark` |
| Simulated packet loss | Recorded benchmark runs retained 100% association at 0%, 5%, 10%, and 20% configured loss | `docs/BENCHMARKS.md` |

Packet-loss RMSE is deliberately not promoted as a headline result.

Some recorded loss runs produced counterintuitive RMSE reductions, so those values remain documented for investigation rather than being presented as improvements.

<!-- FAILURE / REACQUISITION GIF Only add this if the behavior can be reproduced honestly in the live system. Suggested path: docs/figures/recovery.gif Then uncomment: ![Vanguard-X coasting and reacquisition](docs/figures/recovery.gif) -->

---

## Requirements traceability

A lightweight verification map ties important system requirements to executable evidence.

| ID | Requirement | Verification |
|---|---|---|
| REQ-TRK-01 | Position RMSE shall remain below 15 m under the frozen three-sensor benchmark workload | `FullBenchmark`: 10.86 m |
| REQ-TRK-02 | Association shall remain correct for the frozen baseline workload | `FullBenchmark`: 100.0% association, 0 false tracks |
| REQ-TRK-03 | A confirmed track shall enter COASTING after configured missed detections | `PacketLossIT` |
| REQ-TRK-04 | Reacquisition shall preserve canonical track identity | `PacketLossIT` |
| REQ-EST-01 | Measurement fusion shall reduce position error relative to raw observations under the benchmark model | `FullBenchmark`: 29.24 m -> 10.82 m |
| REQ-SYS-01 | A restarted Kafka consumer shall resume after its committed offset | `KafkaRecoveryIT` |
| REQ-SYS-02 | Identical seeded replay inputs shall produce identical estimator output | `ReplayIT`: 0 replay delta |
| REQ-EVT-01 | Repeated identical breach state shall not emit repeated entry events | `FullBenchmark`: 1,000 -> 1 |
| REQ-PERF-01 | Tracking throughput shall exceed 20,000 reports/s at 200 targets on the frozen operational configuration | `FullBenchmark`: 21,348 reports/s |

These IDs are project-level traceability identifiers, not external program or defense-system requirements.

---

## Sensor interface

The telemetry ingress boundary is an explicit Protobuf contract:

`vanguard-protocol/src/main/proto/sensor_report.proto`

```protobuf
message SensorReport {
    string sensor_id = 1;
    int64 timestamp_ms = 2;

    double sensor_x = 3;
    double sensor_y = 4;

    double range = 5;
    double azimuth = 6;

    double signal_strength = 7;
    int64 sequence_number = 8;
}
```

The Netty gateway decodes each report and validates it before the report enters the Kafka pipeline.

| Field | Type | Meaning | Gateway validation |
|---|---|---|---|
| `sensor_id` | string | Sensor identity | Must be non-blank |
| `timestamp_ms` | int64 | Observation event time | Must be positive; default max age 30 s; more than 5 s future skew rejected |
| `sensor_x` | double | Fixed sensor X position | Carried into decoded report |
| `sensor_y` | double | Fixed sensor Y position | Carried into decoded report |
| `range` | double | Raw radial measurement | Must be non-negative |
| `azimuth` | double | Raw bearing in radians | Must be finite and within [-2π, 2π]; normalized downstream |
| `signal_strength` | double | Observation-quality metadata | No packet-level range restriction currently applied |
| `sequence_number` | int64 | Per-sensor sequence metadata | Must be non-negative |

The azimuth ingress envelope is intentionally permissive enough to accept one wrapped revolution in either direction.

Bearing residuals are normalized downstream by the estimator before the EKF update.

Malformed or invalid reports are rejected before entering the tracking stream.

Additional contracts for fused tracks and spatial events live in:

`vanguard-protocol/src/main/proto/`

---

## Module map

| Module | Responsibility | Scope |
|---|---|---|
| `vanguard-protocol` | Protobuf contracts for reports, fused tracks, and events | Interface boundary |
| `vanguard-simulator` | Deterministic trajectories, sensor models, noise, bias, and network impairments | Test infrastructure |
| `vanguard-gateway` | Netty UDP ingestion, decoding, validation, sequencing, deduplication, Kafka production | Network edge |
| `vanguard-tracking` | EKF, motion/measurement models, Mahalanobis association, lifecycle, NIS/NEES | Core estimation |
| `vanguard-spatial` | Geofence evaluation and stateful spatial-event generation | Event logic |
| `vanguard-api` | Runtime composition, REST/WebSocket API, Redis access, Micrometer telemetry | Service/runtime layer |
| `vanguard-ui` | React/TypeScript operational UI, MapLibre map, covariance ellipses, events, metrics | Presentation |
| `benchmarks` | Accuracy, fusion, throughput, latency, replay, loss, and covariance evaluation | Performance/evaluation |
| `integration-tests` | Testcontainers Kafka/Redis pipeline, restart, replay, and loss verification | System verification |

The UI is decomposed into an application shell, tactical map, dashboard panels, tabs, shared UI primitives, configuration, hooks, and utilities rather than a monolithic entrypoint.

---

## Benchmark methodology

The frozen performance campaign uses:

- a fixed workload and configuration;
- warm-up before measurement;
- a fixed send schedule;
- repeated runs;
- three-run medians rather than best-run selection;
- committed raw outputs;
- a 300 m operational spatial grid;
- a separate 2,000 m coarse-grid ablation;
- an in-process tracking-latency definition kept separate from live end-to-end telemetry.

### Measured operational-grid throughput

| Targets | Reports/s |
|---:|---:|
| 50 | 48,858 |
| 200 | 21,348 |
| 500 | 14,962 |
| 1,000 | 16,696 |

The measured points are reported as-is. No monotonic-scaling claim is made.

### At 200 targets

```
p50  13.49 ms
p95  18.45 ms
p99  20.73 ms
```

The 300 m operational spatial grid outperformed the 2,000 m coarse-grid configuration at every measured target count in the frozen benchmark.

Both configurations use the spatial index; the comparison isolates spatial-grid granularity rather than indexing versus no indexing.

See [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) for the full methodology, coarse-grid comparison, limitations, and performance-engineering experiments.

---

## Verification and CI

The repository uses:

- **JUnit 5** for estimation, association, lifecycle, gateway, and spatial logic;
- **Testcontainers** with real Kafka and Redis for integration verification;
- **GitHub Actions** for build and integration gates;
- **CodeQL** static analysis;
- **CycloneDX** SBOM generation;
- Production **Docker image** builds and Compose smoke testing.

The CI pipeline is treated as part of the engineering evidence rather than presentation-only infrastructure.

---

## Observability

Runtime telemetry is exposed through Micrometer/Prometheus and visualized in both Grafana and the operator UI.

Live telemetry includes ingest throughput, track counts, end-to-end p99 latency, Kafka lag, gateway drops, Redis persistence pressure, component health, and spatial events.

<!-- OBSERVABILITY SCREENSHOT Recommended screenshot: Vanguard Analytics tab or Grafana dashboard Suggested path: docs/figures/live-analytics.png Then uncomment: ![Vanguard-X live telemetry dashboard](docs/figures/live-analytics.png) -->

The UI deliberately separates live telemetry from the frozen benchmark snapshot so transient runtime values are never presented as the measured 20.73 ms in-process benchmark result.

---

## Documentation

Deeper design and verification material lives under `docs/`:

- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) -- frozen performance and correctness results
- [`docs/architecture/`](docs/architecture/) -- architecture decisions and tradeoffs
- [`docs/mathematics/`](docs/mathematics/) -- estimator and association notes
- [`docs/performance/`](docs/performance/) -- performance methodology and experiments
- [`docs/reliability/`](docs/reliability/) -- loss, replay, and failure models
- [`docs/security/`](docs/security/) -- security-related engineering notes
- [`docs/interview-talking-points.md`](docs/interview-talking-points.md) -- concise technical discussion notes

---

## Known limitations

- **Nearest-neighbour association.** The current association model can fail in dense or ambiguous multi-target conditions. JPDA/MHT are deferred.
- **Runtime estimator selection.** The frozen full-system baseline uses the constant-velocity EKF. An IMM implementation has been evaluated separately but has not been promoted to the default runtime tracker.
- **Single-host deployment.** The reference deployment uses Docker Compose rather than Kubernetes or a distributed production topology.
- **Single Redis instance.** A production deployment would require replicated or clustered state infrastructure.
- **Internal transport security.** Development paths do not currently use TLS.
- **External basemap dependency.** The UI uses an external ArcGIS raster basemap; offline or self-hosted map tiles are not bundled.

These are explicit scope boundaries, not hidden production claims.

---

## Quick start

### Requirements

- Docker
- Docker Compose

### Start the complete reference stack:

```bash
docker compose up --build
```

### Services:

| Service | URL |
|---|---|
| Operator UI | `http://localhost:3000` |
| API via UI | `http://localhost:3000/api/...` |
| Direct API | `http://localhost:8081` (configurable) |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3001` |
| UDP ingest | `:5000/udp` |

The production UI proxies `/api/*` and `/ws/*` through nginx, so browser-side code does not require a separate backend hostname or port.

### Stop cleanly:

```bash
docker compose down --remove-orphans
```

### Run module-level verification:

```bash
mvn clean verify -pl '!integration-tests'
mvn verify -pl integration-tests -am
```

### Build the frontend:

```bash
cd vanguard-ui
npm ci
npm run build
```

---

## License

Released under the [MIT License](LICENSE).