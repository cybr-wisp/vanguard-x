# ADR-001: System Design

**Status:** Accepted
**Date:** 2026-08-30

## Context

VANGUARD-X is a multi-sensor geospatial tracking engine that fuses raw UDP telemetry through an Extended Kalman Filter pipeline. The system must ingest 10,000+ simulated radar updates per second, produce filtered track state in real time, evaluate geofence boundaries, and deliver results to a browser-based tactical map.

The architecture must tolerate downstream processor failures and load spikes without losing telemetry already committed to the durable streaming layer. UDP loss remains possible by design and is detected through per-sensor sequence numbers, surfaced as an operational metric, and incorporated into tracking uncertainty.

## Decision

### Modular pipeline

The system is decomposed into seven modules: one shared schema library, five Java services, and one browser frontend. Each service has a single responsibility and communicates through Kafka topics at boundaries where durability, replay, independent scaling, or asynchronous recovery are required.

| Module | Responsibility | Upstream | Downstream |
|--------|---------------|----------|------------|
| `vanguard-protocol` | Shared Protobuf schema definitions and generated Java bindings | (compile-time dependency) | All Java modules |
| `vanguard-simulator` | Generate deterministic radar targets with configurable noise in polar coordinates | (external trigger) | UDP to gateway |
| `vanguard-gateway` | Receive UDP, decode Protobuf, validate, sequence-track | UDP socket | Kafka: `raw-tracks` |
| `vanguard-tracking` | Data association + Extended Kalman Filter state estimation | Kafka: `raw-tracks` | Kafka: `filtered-tracks`, Redis, PostGIS |
| `vanguard-spatial` | Point-in-polygon geofence evaluation, alert state machine | Kafka: `filtered-tracks` | Kafka: `alerts` |
| `vanguard-api` | REST endpoints, WebSocket streaming, metrics, SBOM serving | Kafka: `filtered-tracks`, `alerts`, Redis | HTTP/WS to clients |
| `vanguard-ui` | Mapbox tactical map, metrics panel, chaos controls | WebSocket from API | (browser) |

### UDP over TCP for telemetry ingestion

Real radar and sensor systems transmit over UDP because telemetry is time-sensitive and lossy-tolerant: a missed position report from 200ms ago is less useful than the one arriving now. TCP's retransmission and ordering guarantees add latency that degrades tracking performance. The gateway detects packet loss through per-sensor sequence gaps and surfaces the loss rate as an operational metric rather than relying on the transport layer for reliability.

### Protobuf over JSON for wire format

At 10,000+ messages/second, serialization overhead matters. Protobuf produces compact binary payloads with faster encode/decode than text-based formats. Generated message types reduce runtime parsing ambiguity and provide a versionable binary contract across modules. The actual payload reduction compared to equivalent JSON will be measured in the benchmark suite.

Three schemas define the system's data model:
- `SensorReport` -- raw noisy radar observation (range, azimuth) from a single sensor
- `FusedTrack` -- EKF-refined Cartesian state estimate with covariance
- `TrackEvent` -- geofence boundary crossing alert

### Kafka at durable processing boundaries

Kafka is introduced at boundaries where durability, replay, independent scaling, or asynchronous recovery are required. The system uses three topics:

- `raw-tracks` between gateway and tracking: decouples network I/O from state estimation, enables replay of historical sensor data by resetting consumer offsets, provides durability and failure recovery for accepted observations
- `filtered-tracks` between tracking and spatial: allows independent scaling of the geofence evaluator, provides backpressure visibility through consumer lag
- `alerts` between spatial and API: ensures alert events survive API restarts

Not every inter-module boundary uses Kafka. Direct method calls or in-process handoffs are used where the overhead of a message broker is unjustified. The local development environment uses Kafka in KRaft mode (no ZooKeeper dependency).

**Stateful tracking constraint:** Although Kafka permits multiple consumers per group, the V1 tracking stage operates as a single logical stateful processor. Multi-target data association requires visibility across all candidate tracks, so naively partitioning raw observations across independent tracking workers could produce inconsistent associations. Horizontal partitioning of the tracker requires an explicit spatial partitioning and track-handoff strategy and is deferred beyond V1. Other stateless or independently partitionable stages, such as spatial evaluation, may scale horizontally.

### EKF for state estimation

The Extended Kalman Filter maintains a Cartesian state vector:

```
x_k = [x, y, vx, vy]^T
```

The simulated radar sensors produce observations in polar coordinates relative to each sensor's position:

```
z_k = [r, theta]^T

where:
  r     = sqrt((x - x_s)^2 + (y - y_s)^2)
  theta = atan2(y - y_s, x - x_s)
```

The observation function `h(x_k)` is nonlinear, which is what makes this genuinely an Extended Kalman Filter rather than a standard KF. The EKF linearizes `h` at each step by computing the Jacobian `H_k = dh/dx`, then applies the standard predict-update cycle.

This design means the sensor is not handing the tracker an already-computed Cartesian coordinate; it provides an imperfect radar observation that the tracker must transform into state. The EKF also produces a covariance matrix quantifying uncertainty in each state variable, which drives the confidence ellipses on the tactical map. The Mahalanobis distance gate prevents wild measurements from corrupting existing tracks.

### Persistence topology

Redis and PostGIS sit off the state-processing layers (tracking and spatial), not the API:

```
                       +-- Redis ----- hot state (live track positions, GEOADD/GEOSEARCH)
Tracking / Spatial ----+
                       +-- PostGIS --- historical persistence (spatial queries over time)
```

The API is a serving layer that reads from Redis and PostGIS. It does not own persistence. This separation ensures the API can be restarted or scaled independently without affecting state durability.

### Delivery semantics

Gateway assigns each accepted report a `(sensor_id, sequence_number)` identity. Kafka processing uses at-least-once delivery. Downstream operations are idempotent with respect to this identity; duplicate observations are detected before state mutation. The system therefore targets effectively-once state updates rather than claiming end-to-end exactly-once processing.

## Data Flow

```
vanguard-protocol
    Shared Protobuf contracts
          |
          v
vanguard-simulator
    Radar truth + noisy observations [r, theta]
          |
         UDP
          v
vanguard-gateway
    Decode / validate / sequence tracking
          |
      raw-tracks (Kafka)
          v
vanguard-tracking  ----+-- Redis (hot state)
    Association / EKF   +-- PostGIS (history)
          |
    filtered-tracks (Kafka)
          v
vanguard-spatial
    Geofence / alert state machine
          |
        alerts (Kafka)
          v
vanguard-api
    State aggregation / REST / WS / metrics
          |
          v
vanguard-ui
```

See `architecture.svg` for the full visual diagram.

## Consequences

- Every module can be tested in isolation by stubbing its Kafka topics
- Kafka in KRaft mode eliminates the ZooKeeper dependency for local development
- The EKF implementation must handle the nonlinear polar-to-Cartesian observation model; Jacobian computation uses double precision throughout
- Protobuf schema changes require recompilation of `vanguard-protocol` and all downstream modules
- Redis is an additional infrastructure dependency but eliminates the need for custom spatial data structures
- The API is a stateless serving layer; persistence responsibility stays with tracking and spatial modules
- UDP packet loss is an expected operational condition, not a failure mode; loss rates are monitored and factored into track quality metrics