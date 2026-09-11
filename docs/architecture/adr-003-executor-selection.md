# ADR-003: Tracking Executor Selection

## Status

Open experiment.

## Context

The tracking stage performs primarily CPU-bound work:

- EKF state estimation
- Mahalanobis gating
- Hungarian assignment
- spatial candidate search
- lifecycle processing

Netty handles UDP I/O independently on its event loop. The tracking pipeline currently processes Kafka records synchronously in the dedicated tracking-consumer thread.

Two executor models are candidates for future parallelization:

1. bounded fixed-worker executor
2. Java 21 virtual-thread-per-task executor

The existing benchmark suite measures the current tracking implementation but does not yet provide a controlled concurrent comparison of these executor models under multiple in-flight tracking workloads.

## Decision

Retain the dedicated tracking-consumer execution model until executor parallelism demonstrates a measurable improvement under a controlled benchmark.

A future comparison should measure:

- throughput
- p50, p95, and p99 latency
- CPU utilization
- Kafka consumer lag
- executor queue growth
- saturation behavior

## Executor considerations

Virtual threads reduce scheduling overhead for large numbers of blocking tasks but do not increase available CPU capacity.

Because the tracking hot path is predominantly CPU-bound, executor selection depends on measured scaling and saturation behavior rather than task-creation cost alone.

A bounded fixed-worker executor may provide more explicit control over CPU concurrency and queue growth. A virtual-thread executor may still be useful if future pipeline stages introduce significant blocking I/O.

## Current benchmark baseline

The frozen indexed benchmark reports:

| Targets | Throughput |
|---:|---:|
| 50 | 48,858 reports/s |
| 200 | 21,348 reports/s |
| 500 | 14,962 reports/s |
| 1,000 | 16,696 reports/s |

At 200 targets, indexed in-process tracking latency is:

- p50: 13.49 ms
- p95: 18.45 ms
- p99: 20.73 ms

These measurements establish the baseline for evaluating future executor changes.

## Saturation indicators

For the current synchronous consumer model, sustained Kafka consumer lag is the primary saturation indicator.

If a bounded executor is introduced, queue depth and queue growth should also be monitored. Executor changes should be evaluated against the existing frozen workload using the same benchmark environment and input seeds.

## Revisit criteria

Revisit this decision when one of the following occurs:

- Kafka consumer lag becomes sustained under the target workload
- tracking latency exceeds the benchmark envelope
- blocking work is introduced into the tracking path
- profiling identifies exploitable parallelism in the tracking stage

Any executor change should include a reproducible benchmark artifact comparing the new configuration against the current baseline.
