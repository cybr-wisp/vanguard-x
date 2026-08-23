# VANGUARD-X

> A high-throughput geospatial telemetry pipeline built on Java 21 virtual threads, processing 10k+ simulated radar track updates per second with real-time anomaly detection, spatial geofencing, and live tactical visualization.

<!-- TODO (Week 3): 60-90 second demo GIF here -->

## Architecture

```
                          UDP (Protobuf)
  [Mock Radar] ─────────────────────────> [Netty UDP Server]
  75 tracks @ 10 Hz                            │
  ~750 pkt/sec                                 │ virtual threads
                                               ▼
                                    ┌─── raw-tracks (Kafka) ───┐
                                    │                          │
                                    ▼                          │
                             [EKF Processor]                   │
                             TrackStateManager                 │
                             noise filtering                   │
                                    │                          │
                                    ▼                          │
                         filtered-tracks (Kafka)               │
                                    │                          │
                                    ▼                          │
                          [Geofence Engine]                    │
                          point-in-polygon                     │
                          alert dedup                          │
                             │          │                      │
                             ▼          ▼                      │
                       alerts (Kafka)  Redis GEOADD            │
                             │          │                      │
                             ▼          ▼                      │
                       [Spring Boot API]                       │
                       REST + WebSocket (10 fps)               │
                             │                                 │
                             ▼                                 │
                    [Tactical Dashboard]                       │
                    Mapbox GL JS dark theme                    │
                    live tracks + geofences                    │
                    metrics overlay                            │
                    chaos panel                                │
```

## Modules

| Module | Description |
|---|---|
| `vanguard-simulator` | Mock radar generator. Sends Protobuf-encoded tracks over UDP |
| `vanguard-pipeline` | Core engine. Netty ingestion, EKF, geofencing, Kafka, Redis, Spring Boot API |
| `vanguard-ui` | React + Mapbox GL JS tactical dashboard |

## Quick Start

```bash
# Infrastructure
docker compose up -d

# Build Java
mvn clean package -DskipTests

# Run pipeline (terminal 1)
java -jar vanguard-pipeline/target/vanguard-pipeline-1.0.0-SNAPSHOT.jar

# Run simulator (terminal 2)
java -jar vanguard-simulator/target/vanguard-simulator-1.0.0-SNAPSHOT.jar

# Run dashboard (terminal 3)
cd vanguard-ui && npm install && npm run dev
```

## Performance

| Metric | Target | Measured |
|---|---|---|
| Ingestion throughput | 10,000 updates/sec | TBD |
| End-to-end latency (p50) | < 50 ms | TBD |
| End-to-end latency (p99) | < 200 ms | TBD |
| Concurrent tracks | 100+ | TBD |
| Recovery after node kill | < 10 sec | TBD |

See [docs/benchmarks.md](docs/benchmarks.md) for full analysis.

## Tech Stack

- **Java 21** - virtual threads for concurrent track processing
- **Netty 4.1** - UDP DatagramChannel ingestion
- **Protocol Buffers 4.x** - binary wire format
- **Apache Kafka 3.7** - KRaft mode inter-stage messaging
- **Redis 7.4** - GEOADD/GEOSEARCH spatial indexing
- **Spring Boot 3.3** - REST, WebSocket, Actuator
- **PostGIS** - historical track persistence
- **Mapbox GL JS** - dark tactical map rendering
- **Prometheus + Micrometer** - metrics export
- **CycloneDX** - SBOM generation

## Documentation

- [EKF Math Specification](docs/ekf_math_spec.md)
- [Benchmarks](docs/benchmarks.md)
- [Chaos Scenarios](docs/chaos_scenarios.md)

## SBOM

```bash
mvn package    # generates target/bom.json (CycloneDX 1.5)
```

## License

MIT
