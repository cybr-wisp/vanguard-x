# Data Association with Mahalanobis Gating

## Problem

Before a sensor report can update a track, Vanguard must decide whether the
report plausibly belongs to that track. Naive Euclidean distance ignores
uncertainty: a 100m residual might be alarming for a precise sensor but
perfectly normal for a noisy one.

## Mahalanobis distance

The squared Mahalanobis distance accounts for both the magnitude and the
shape of uncertainty:

    d^2 = y^T * S^{-1} * y

where:
- y is the innovation (measurement residual): z - h(x_pred)
- S is the innovation covariance: H * P * H^T + R

S combines the predicted state uncertainty (through H*P*H^T) with the
sensor's own measurement noise (R). A noisier sensor produces a larger S,
which makes the same innovation y yield a smaller d^2. This is the correct
behavior: large residuals are expected from noisy sensors.

## Gating threshold

The threshold gamma comes from the chi-square distribution with degrees of
freedom equal to the measurement dimension (2 for range/bearing):

    95% confidence: gamma = 5.991
    99% confidence: gamma = 9.210
    99.5%:          gamma = 10.597

Vanguard uses gamma = 9.21 (99%) as the default. This means roughly 1% of
true associations are rejected by the gate, which is an acceptable trade-off
against letting outliers corrupt the estimate.

## Association strategy

v1.0 uses nearest-neighbour association inside the gate:

1. For each observation, predict all candidate tracks to the observation time
2. Compute the innovation and S for each candidate
3. Reject candidates with d^2 > gamma (outside the gate)
4. Select the candidate with the smallest d^2 among those inside

Batch association is greedy: once a track is claimed by one observation,
it is not available for subsequent observations in the same batch.

## Why not Euclidean distance

Euclidean distance treats all directions equally and ignores uncertainty.
Consider two tracks: one with tight covariance (just updated) and one with
large covariance (coasting for several seconds). A 200m residual should
be rejected for the first track but might be plausible for the second.
Mahalanobis gating handles this correctly; Euclidean does not.

## Limitations

Nearest-neighbour association can fail in dense multi-target scenarios
where the gates of two tracks overlap and contain each other's observations.
JPDA (Joint Probabilistic Data Association) and MHT (Multiple Hypothesis
Tracking) address this but are explicitly deferred to future work per the
build guide's scope control.

## Implementation

- `MahalanobisGate.java`: computes d^2, tests against threshold
- `DataAssociator.java`: nearest-neighbour selection inside the gate
- `ExtendedKalmanFilter.computeInnovation()`: produces y and S without
  modifying filter state, enabling gating before committing to an update
