# ADR-003: Tracking Executor Selection

## Status

Open experiment â€” no production winner is claimed.

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

The repository does not currently contain a completed controlled executor benchmark that justifies declaring either configuration the production winner.

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
| 50 | 35,728 reports/s |
| 200 | 18,546 reports/s |
| 500 | 12,485 reports/s |
| 1,000 | 9,771 reports/s |

At 200 targets, indexed in-process tracking latency is:

- p50: 16.74 ms
- p95: 23.92 ms
- p99: 37.72 ms

These values characterize the existing tracking implementation. They are not executor-comparison results.

## Saturation signal

For the current implementation, sustained Kafka consumer lag is the clearest leading saturation indicator. If a bounded worker executor is introduced later, sustained queue growth would provide an additional leading signal.

A numerical executor-specific saturation point will only be documented after a controlled load campaign measures it.

## Consequences

- no unsupported fixed-versus-virtual winner
- frozen benchmark claims remain unchanged
- executor parallelization remains an explicit future experiment
- changes to concurrency require a new benchmark artifact
