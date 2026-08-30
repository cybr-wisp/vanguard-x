# VANGUARD-X

[![CI](https://github.com/cybr-wisp/vanguard-x/actions/workflows/ci.yml/badge.svg)](https://github.com/cybr-wisp/vanguard-x/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Kafka-KRaft-231F20?logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Protobuf](https://img.shields.io/badge/Protobuf-4.27-4285F4?logo=google&logoColor=white)](https://protobuf.dev/)
[![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![PostGIS](https://img.shields.io/badge/PostGIS-3.4-336791?logo=postgresql&logoColor=white)](https://postgis.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> Multi-sensor geospatial tracking engine that fuses raw UDP telemetry through an Extended Kalman Filter pipeline, with real-time geofence alerting, Kafka event streaming, and a tactical map interface.

---

## Architecture

![VANGUARD-X Architecture](docs/architecture/architecture.png)

Simulated radar stations produce noisy polar observations `[range, azimuth]` over UDP. A Netty gateway decodes, validates, and sequence-tracks each packet before committing it to Kafka. The tracking processor runs data association and an Extended Kalman Filter to fuse observations into smooth Cartesian state estimates `[x, y, vx, vy]` with covariance. A spatial engine evaluates fused tracks against geofence polygons and publishes boundary-crossing alerts. A Spring Boot API serves fused tracks and alerts over REST and WebSocket to a browser-based tactical map.

## Modules

| Module | Description |
|--------|-------------|
| `vanguard-protocol` | Shared Protobuf schemas: `SensorReport`, `FusedTrack`, `TrackEvent` |
| `vanguard-simulator` | Deterministic multi-target world simulator with configurable noise and polar output |
| `vanguard-gateway` | Netty UDP ingestion server with Protobuf decoding, validation, and sequence tracking |
| `vanguard-tracking` | Data association (Mahalanobis gating) and Extended Kalman Filter state estimation |
| `vanguard-spatial` | Point-in-polygon geofence engine with alert state machine and event deduplication |
| `vanguard-api` | Spring Boot REST/WebSocket server, Prometheus metrics, CycloneDX SBOM endpoint |
| `vanguard-ui` | React tactical map (MapLibre), metrics panel, and chaos engineering controls |

## Quick Start

**Prerequisites:** Java 21+, Maven 3.9+, Docker

```bash
# clone and build
git clone https://github.com/cybr-wisp/vanguard-x.git
cd vanguard-x
mvn compile

# bring up infrastructure (Kafka, Redis, PostGIS, Prometheus, Grafana)
docker-compose up -d

# run the pipeline
mvn -pl vanguard-api spring-boot:run

# in a second terminal, start the simulator
mvn -pl vanguard-simulator exec:java
```

Open `http://localhost:3000` for Grafana dashboards and the tactical map UI.

## Key Design Decisions

**UDP over TCP** -- Telemetry is time-sensitive and lossy-tolerant. A missed position report from 200ms ago is less useful than the one arriving now. Packet loss is detected through per-sensor sequence numbers and surfaced as an operational metric.

**Polar observations with EKF** -- Sensors output `[range, azimuth]`, not pre-computed Cartesian coordinates. The nonlinear observation model `h(x)` requires Jacobian linearization at each step, making this a genuine Extended Kalman Filter rather than a standard KF.

**Kafka at durable boundaries only** -- Kafka sits between stages that need durability, replay, or independent scaling. The V1 tracking stage operates as a single stateful processor because multi-target data association requires global visibility across candidate tracks.

**At-least-once with idempotent downstream** -- Each observation carries a `(sensor_id, sequence_number)` identity. Downstream operations are idempotent with respect to this identity, targeting effectively-once state updates without claiming exactly-once semantics.

For the full architecture rationale, see [ADR-001: System Design](docs/architecture/system-design.md).

## Tech Stack

**Backend:** Java 21 (virtual threads), Spring Boot 3.3, Netty, Apache Kafka (KRaft), Protocol Buffers

**Storage:** Redis (GEOADD/GEOSEARCH spatial indexing), PostGIS (historical track persistence)

**Frontend:** React, TypeScript, MapLibre GL JS

**Observability:** Prometheus, Grafana, Micrometer, CycloneDX SBOM

**Infrastructure:** Docker Compose, GitHub Actions CI/CD, CodeQL

## Project Structure

```
vanguard-x/
├── vanguard-protocol/       Protobuf schemas and generated bindings
├── vanguard-simulator/      UDP mock radar generator
├── vanguard-gateway/        Netty UDP ingestion server
├── vanguard-tracking/       EKF + data association engine
├── vanguard-spatial/        Geofence evaluation engine
├── vanguard-api/            Spring Boot REST/WebSocket server
├── vanguard-ui/             React tactical map dashboard
├── integration-tests/       End-to-end pipeline tests
├── benchmarks/              Throughput and latency analysis
├── infra/                   Docker, Kafka, Prometheus, Grafana configs
└── docs/                    Architecture, math specs, reliability docs
```

## Documentation

| Document | Description |
|----------|-------------|
| [System Design](docs/architecture/system-design.md) | ADR-001: architecture decisions and rationale |
| [EKF Derivation](docs/mathematics/ekf-derivation.md) | Mathematical formulation of the polar-to-Cartesian EKF |
| [State Estimation](docs/mathematics/state-estimation.md) | State vector, process model, and measurement model |
| [Failure Model](docs/reliability/failure-model.md) | Failure modes, detection, and recovery strategies |
| [Threat Model](docs/security/threat-model.md) | Security boundaries and attack surface analysis |
| [Benchmark Results](docs/performance/results.md) | Throughput vs. latency analysis at varying load |

## License

[MIT](LICENSE)