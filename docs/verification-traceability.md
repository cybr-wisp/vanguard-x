# Verification Traceability

Vanguard-X maps system and performance requirements to executable tests,
integration tests, or retained benchmark artifacts.

| Requirement | Verification | Evidence |
| --- | --- | --- |
| REQ-TRK-01 | Position RMSE is measured against the frozen tracking workload. | `benchmarks/src/main/java/com/vanguard/benchmark/FullBenchmark.java`; `benchmarks/results/accuracy.csv` |
| REQ-TRK-02 | Association accuracy and false-track behavior are measured under the frozen multi-target workload. | `benchmarks/src/main/java/com/vanguard/benchmark/FullBenchmark.java`; `benchmarks/results/accuracy.csv` |
| REQ-TRK-03 | Missed observations move a confirmed track into `COASTING` and increase uncertainty. | `integration-tests/src/test/java/com/vanguard/PacketLossIT.java`; `vanguard-tracking/src/test/java/com/vanguard/tracking/association/AssociationAndLifecycleTest.java` |
| REQ-TRK-04 | Reacquisition preserves canonical track identity without leaving duplicate tracks. | `integration-tests/src/test/java/com/vanguard/PacketLossIT.java`; `vanguard-tracking/src/test/java/com/vanguard/tracking/association/AssociationAndLifecycleTest.java` |
| REQ-EST-01 | Estimator performance is compared against raw measurement error and alternate estimator configurations using controlled truth/noise sequences. | `docs/mathematics/estimation-evaluation.md`; `benchmarks/src/main/java/com/vanguard/benchmark/FullBenchmark.java` |
| REQ-SYS-01 | A restarted Kafka consumer resumes from its committed offset. | `integration-tests/src/test/java/com/vanguard/KafkaRecoveryIT.java` |
| REQ-SYS-02 | Identical seeded replay produces identical estimator output and Kafka payload. | `integration-tests/src/test/java/com/vanguard/ReplayIT.java` |
| REQ-EVT-01 | Repeated identical zone state does not emit duplicate edge-triggered alerts. | `vanguard-spatial/src/test/java/com/vanguard/spatial/SpatialTest.java`; `vanguard-spatial/src/main/java/com/vanguard/spatial/AlertStateMachine.java` |
| REQ-PERF-01 | Tracking throughput exceeds 20,000 reports/sec at the 200-target frozen workload. | `benchmarks/results/optimized-three-run/run-2.txt`; `docs/BENCHMARKS.md` |

## Verification layers

Unit and component tests cover estimator, association, lifecycle, and state-machine behavior.

Integration tests cover Kafka recovery, deterministic replay, packet-loss
reacquisition, Redis persistence, and pipeline behavior.

Benchmark artifacts retain the measured accuracy, association behavior, latency,
and throughput for the frozen synthetic workload.

Performance requirements are tied to retained benchmark artifacts rather than
inferred from test success alone.
