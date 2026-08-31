# ADR-003: Production Executor Selection

## Status
Pending (fill in after benchmark)

## Context
The tracking pipeline performs CPU-bound work (EKF matrix operations,
Mahalanobis distance, association search). Netty already provides
efficient non-blocking I/O, so the executor choice affects only the
processing stage between Kafka consume and produce.

Two configurations were benchmarked:
1. Bounded fixed-worker thread pool (N workers, bounded queue)
2. Virtual-thread-per-task executor (Java 21)

## Benchmark conditions

- Scenario: reference 22-target scenario at [MEASURED] reports/sec
- Warm-up: 1000 iterations discarded
- Measurement: 10000 iterations
- Hardware: [FILL IN]
- JVM: OpenJDK 21, default GC

## Results

| Metric | Fixed-N workers | Virtual threads |
|--------|----------------|-----------------|
| Throughput (ops/s) | [MEASURED] | [MEASURED] |
| p50 latency (ms) | [MEASURED] | [MEASURED] |
| p95 latency (ms) | [MEASURED] | [MEASURED] |
| p99 latency (ms) | [MEASURED] | [MEASURED] |
| Peak memory (MB) | [MEASURED] | [MEASURED] |
| Queue overflows | [MEASURED] | [MEASURED] |

## Decision

[SELECTED CONFIGURATION] was chosen because [EVIDENCE].

## Rationale

Virtual threads provide no inherent speedup for CPU-bound work. Their
advantage is in reducing the cost of blocking I/O (file, network, sleep).
Since the tracking pipeline's hot path is matrix multiplication and
distance computation (CPU-bound), [EXPECTED OUTCOME].

The decision is justified by measurements, not fashion or assumption.

## Consequences

- The selected executor is configured in the TrackingPipelineConsumer
- Queue capacity is set to [N] based on the saturation point
- This decision can be revisited if the workload profile changes
  (e.g., if Redis writes become a bottleneck, virtual threads may help)
