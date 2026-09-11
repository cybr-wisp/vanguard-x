# Benchmark Reproducibility

Vanguard-X performance results are retained as benchmark artifacts rather than
inferred from unit-test success.

## Build

```text
mvn -B clean verify -pl '!integration-tests'
```

## Integration verification

Integration tests use Testcontainers and require a running Docker environment.

```text
mvn -B verify -pl integration-tests -am
```

The integration suite covers Kafka recovery, packet-loss reacquisition,
pipeline behavior, and deterministic replay.

## Benchmark results

Retained benchmark outputs are stored under:

```text
benchmarks/results/
```

The frozen benchmark methodology and recorded measurements are documented in
docs/BENCHMARKS.md.

Results apply to the repository's deterministic synthetic workload and the
recorded execution environment. They are not general throughput or accuracy
guarantees for other hardware, workloads, sensor models, or deployment
topologies.

## Reproduction notes

For comparisons against retained results:

1. use Java 21;
2. build from a clean working tree;
3. preserve the benchmark workload and seed configuration;
4. record the commit SHA;
5. record CPU, operating system, JVM, and relevant container/runtime versions;
6. compare repeated runs rather than selecting a single best run.

Performance regressions or improvements should be reported separately from the
frozen historical baseline instead of silently replacing retained results.
