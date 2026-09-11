# Packet Loss Behavior

## Scope

Vanguard includes a deterministic packet-loss sweep in `FullBenchmark`.

The currently committed sweep uses `ScenarioLoader.minimalScenario()`:

- 2 deterministic targets
- 1 sensor
- 30-second scenario
- packet-loss rates of 0%, 5%, 10%, and 20%

These results characterize the tracker under controlled packet loss. They
must not be presented as evidence from the separate 22-target reference
scenario.

## Expected behavior

1. Confirmed tracks with sufficient recent observations continue through
   short gaps using prediction.

2. Tracks near the confirmation threshold may fail to confirm when required
   observations are lost.

3. After `missesToCoast` consecutive missed observation cycles, confirmed
   tracks transition to `COASTING`.

4. When valid observations resume, a coasting track can transition back to
   `CONFIRMED`.

5. If loss persists beyond `missesToDrop`, the track transitions to
   `DROPPED`.

## Measured evidence

The frozen benchmark campaign reports:

| Packet loss | Position RMSE | Association accuracy | Peak coasting tracks | Reacquisitions | Max concurrent duplicate tracks | Fragmentation |
|---:|---:|---:|---:|---:|---:|---:|
| 0% | 10.86 m | 100.0% | 1 | 8 | 2 | 2 |
| 5% | 7.63 m | 100.0% | 1 | 7 | 1 | 2 |
| 10% | 7.48 m | 100.0% | 1 | 7 | 1 | 2 |
| 20% | 7.72 m | 100.0% | 1 | 9 | 1 | 2 |

The lower RMSE observed at non-zero loss is treated as a measurement anomaly. A controlled follow-up experiment is needed to determine whether the change is caused by sample selection, lifecycle behavior, measurement timing, or estimator dynamics.

The benchmark measures observed tracking behavior under the stated workload;
it does not establish a general packet-loss tolerance limit.

## Evidence boundary

A packet-loss campaign using the full 22-target reference scenario has not
yet been frozen as a committed benchmark artifact.

Until that follow-up experiment is completed, the packet-loss RMSE values remain observational results rather than evidence of improved estimator accuracy.

## Why this matters

Packet loss is a normal condition for UDP telemetry. Vanguard treats missed
observations as an estimator and lifecycle concern rather than as a transport
failure that terminates the tracking process.
