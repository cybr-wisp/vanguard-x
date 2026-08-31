# Tracking Accuracy Evaluation

## Methodology

Because the simulator owns ground truth, Vanguard can measure estimator
quality objectively rather than relying on screenshots or subjective
assessment.

The evaluation harness (`TrackingEvaluator`) matches canonical tracks back
to hidden ground-truth target IDs for scoring only. No ground-truth
information flows back into the tracker during evaluation.

## Metrics

### Position RMSE

Geometric error of the fused position estimate:

    RMSE_pos = sqrt( (1/N) * sum[ (px_hat - px_true)^2 + (py_hat - py_true)^2 ] )

Lower is better. This is the primary measure of estimation quality.

### Velocity RMSE

Error in estimated velocity components:

    RMSE_vel = sqrt( (1/N) * sum[ (vx_hat - vx_true)^2 + (vy_hat - vy_true)^2 ] )

Velocity estimation is harder because it is not directly observed (only
inferred from successive position updates).

### Association accuracy

Fraction of sensor reports assigned to the correct synthetic target:

    accuracy = correct_associations / total_associations

Each canonical track is assigned a "majority truth" target ID. An association
is correct if the observation's true source matches the track's majority.

### Track fragmentation

How often one ground-truth target becomes multiple canonical tracks.
Fragmentation = number of truth targets mapped to more than one canonical
track. Lower is better; zero means no target was ever split.

### False-track rate

Number of canonical tracks created from clutter or false detections rather
than real targets. Identified by tracks whose majority truth ID is a
clutter marker.

## What to look for

1. **Fusion improves estimation.** EKF position RMSE should be lower than
   raw single-sensor observation error. If it is not, the filter is
   misconfigured.

2. **Degradation conditions.** Sweep noise levels and document where
   RMSE grows rapidly. This is honest engineering, not cherry-picking
   the best result.

3. **Association breaks under density.** When targets are very close
   together, nearest-neighbour association may assign observations to
   the wrong track. Document this threshold.

4. **Fragmentation at track loss.** When a confirmed track loses
   observations and a new tentative track is created nearby, the same
   truth target maps to two canonical tracks. The lifecycle parameters
   (hitsToConfirm, missesToCoast, missesToDrop) control this trade-off.

## Implementation

- `TrackingEvaluator.java`: records samples and association decisions,
  computes all five metrics
- Results should be exported as CSV for external analysis
- Parameter sweeps should use deterministic seeds for reproducibility
