# VANGUARD

A fault-tolerant multi-sensor track correlation and state-estimation system built in Java 21. Vanguard ingests asynchronous synthetic range/bearing reports over UDP, associates observations across imperfect sensors, estimates canonical target state with an Extended Kalman Filter, detects stateful spatial events, and maintains a live operational picture under packet loss, jitter, and processing-node failures.

> **Portfolio scope:** Vanguard is an educational, simulated, unclassified software project. It uses synthetic sensors and synthetic trajectories to demonstrate systems engineering concepts. It is not a real combat system, weapon-control system, or operational targeting tool.

<!-- Demo GIF above the fold -->
<!-- ![Vanguard Demo](docs/figures/demo.gif) -->

## Measured performance

| Metric | Value |
|--------|-------|
| Sustained throughput | [MEASURED] reports/sec |
| End-to-end latency (p99) | [MEASURED] ms |
| Position RMSE (3 sensors) | [MEASURED] m |
| Association accuracy | [MEASURED]% |
| Recovery from processor failure | [MEASURED] sec |

*All values are measured on [hardware]. Design targets are not reported as results.*

## Architecture

```
World Simulator (ground truth)
    |
    v
Sensor A/B/C (noise + bias) --> Network Impairments (loss/jitter/reorder)
    |
    v
Netty UDP Gateway (validate / sequence / dedup)
    |
    v
Kafka: sensor-reports.raw
    |
    v
Tracking Processor (time ordering -> association -> EKF -> lifecycle)
    |
    v
Kafka: tracks.fused          Redis (live state)
    |                              |
    v                              v
Spatial Engine (geofence)    Spring Boot API (REST + WebSocket)
    |                              |
    v                              v
Kafka: track-events          React/TypeScript UI (MapLibre + metrics)
```

## How sensor fusion works

Three imperfect sensors observe the same moving targets, each reporting noisy range and bearing measurements from its own position. The Extended Kalman Filter:

1. **Predicts** target state forward using a constant-velocity motion model
2. **Associates** incoming observations to existing tracks via Mahalanobis gating (chi-square threshold, not Euclidean distance)
3. **Updates** the state estimate and covariance using the Kalman gain
4. **Manages** track lifecycle: TENTATIVE -> CONFIRMED -> COASTING -> DROPPED

The result is a fused position estimate that is more accurate than any single sensor's raw observations, with uncertainty explicitly tracked through covariance matrices.

## Reliability and failure evidence

- **Processor failure:** Kafka consumer rebalance, backlog recovery, and track reacquisition demonstrated with metrics evidence
- **Packet loss:** Confirmed tracks coast through loss and reacquire without creating duplicate tracks
- **Jitter/reordering:** Event-time processing handles out-of-order delivery within a bounded window
- **Traffic burst:** Queue depth and p99 latency spike and recover; drops are measured, not hidden

See `docs/reliability/` for detailed failure scenarios and metrics.

## Quick start

```bash
# One-command startup
docker compose up --build

# The UI is at http://localhost:3000
# The API is at http://localhost:8080
# Grafana dashboards at http://localhost:3001
```

## Module map

| Module | Purpose |
|--------|---------|
| `vanguard-protocol` | Protobuf contracts (SensorReport, FusedTrack, TrackEvent) |
| `vanguard-simulator` | Deterministic world simulator, sensor models, network impairments |
| `vanguard-gateway` | Netty UDP ingestion, validation, dedup, Kafka producer |
| `vanguard-tracking` | EKF, Mahalanobis association, track lifecycle, evaluation harness |
| `vanguard-spatial` | JTS geofence evaluation, alert state machine, event publisher |
| `vanguard-api` | Spring Boot REST + WebSocket, Redis repositories, Micrometer metrics |
| `vanguard-ui` | React/TypeScript tactical map, covariance ellipses, metrics panel |
| `benchmarks` | Load generator, executor comparison, performance results |
| `integration-tests` | Testcontainers-based pipeline, recovery, replay, and loss tests |

## Testing and CI

- JUnit 5 unit tests for estimation math, association, lifecycle, and spatial logic
- Testcontainers integration tests with real Kafka and Redis
- CodeQL static analysis on every push
- CycloneDX SBOM generated per build
- Non-root containers with pinned image versions

## Benchmark methodology

Performance results use a fixed send schedule (coordinated-omission-aware), warm-up phases, and repeated trials. The saturation point and bottleneck hypothesis are documented in `docs/performance/`.

## Known limitations and future work

- Nearest-neighbour association can fail in dense multi-target scenarios; JPDA/MHT deferred to v2
- No cloud deployment (Docker Compose only); Kubernetes deferred
- Single-host Redis; production would use a cluster
- No TLS on internal network paths (development only)
- EKF assumes constant-velocity; IMM (interacting multiple model) would handle maneuvers better

## License

[LICENSE](LICENSE)
