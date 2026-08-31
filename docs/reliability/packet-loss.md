# Packet Loss Behavior

## Scenario

Inject 5% synthetic packet loss via the NetworkImpairmentModel.
Run the reference 22-target scenario and observe tracking behavior.

## Expected behavior

1. Confirmed tracks with sufficient recent observations continue normally.
   The EKF predicts through the gap and the next observation corrects.

2. Tracks near the confirmation threshold (TENTATIVE with 2/3 hits) may
   fail to confirm if their third observation is lost.

3. After `missesToCoast` consecutive missed detection cycles, confirmed
   tracks transition to COASTING. Covariance grows with each prediction.

4. When observations resume, coasting tracks reacquire to CONFIRMED
   without creating a duplicate track (the existing track's gate is
   wide enough to accept the returning observation).

5. If loss persists beyond `missesToDrop` cycles, the track is DROPPED
   and its state is cleaned from Redis.

## Metrics evidence

| Metric | Baseline (0% loss) | 5% loss |
|--------|-------------------|---------|
| Position RMSE | [MEASURED] | [MEASURED] |
| Association accuracy | [MEASURED] | [MEASURED] |
| Coasting tracks (peak) | 0 | [MEASURED] |
| Track fragmentation | 0 | [MEASURED] |
| Packets dropped | 0 | [MEASURED] |

Fill in measured values after running the experiment.

## Why this matters

Packet loss is the normal operating condition for UDP telemetry, not an
exception. The track lifecycle (TENTATIVE/CONFIRMED/COASTING/DROPPED)
makes loss visible as a state-management problem rather than a crash.
