# ADR-003: Tracking Executor Selection

## Status

Open experiment — no controlled concurrent executor winner is claimed.

## Context

The tracking stage performs CPU-heavy work:

- EKF state estimation
- Mahalanobis gating
- Hungarian assignment
- spatial candidate search
- lifecycle processing

Netty handles UDP I/O independently on its event loop. Tracking currently executes synchronously in the dedicated Kafka tracking-consumer thread.

A benchmark harness exists for comparing:

1. bounded fixed-worker executors
2. Java 21 virtual-thread-per-task executors

The repository does not currently contain a frozen controlled concurrent executor benchmark that exercises multiple in-flight tracking work units and measures scaling, queue growth, and saturation.

## Current decision

Keep the simple dedicated tracking-consumer execution model until a controlled executor campaign demonstrates a measurable benefit from additional parallelism.

This avoids presenting an unmeasured concurrency choice as a performance result.

## Why virtual threads are not assumed to win

Virtual threads reduce the cost of large numbers of blocking tasks. They do not create additional CPU execution capacity.

The tracking hot path is dominated by CPU work rather than blocking I/O, so fixed-pool versus virtual-thread selection must be based on measured throughput, tail latency, queue behavior, and CPU utilization.

## Existing full-system evidence

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

These values characterize the existing tracking implementation. They are not executor-comparison results.

## Saturation signal

For the current implementation, sustained Kafka consumer lag is the clearest leading saturation indicator. If a bounded worker executor is introduced later, sustained queue growth would provide an additional leading signal.

A numerical executor-specific saturation point will only be documented after a controlled load campaign measures it.

## Consequences

- no unsupported fixed-versus-virtual winner
- frozen benchmark claims remain unchanged
- executor parallelization remains an explicit future experiment
- changes to concurrency require a new benchmark artifact
