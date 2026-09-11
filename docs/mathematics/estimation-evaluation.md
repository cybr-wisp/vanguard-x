# Estimation Consistency and Held-Out Evaluation

## Protocol

Estimator parameters are fixed before validation.

Development seeds:

- 20267000 through 20267019

Held-out validation seeds:

- 20268000 through 20268029

The two ranges are disjoint. Validation seeds are not used to choose estimator parameters.

## Estimators

Two estimators are evaluated on the same maneuvering trajectories and noisy range/bearing measurements:

1. Joseph-form 4-state constant-velocity EKF.
2. Two-mode Interacting Multiple Model estimator using:
   - low process-noise constant-velocity mode
   - high process-noise maneuver-response mode

The IMM modes share the state [px, py, vx, vy], allowing exact state and covariance mixing.

## Consistency diagnostics

NIS uses the 2-dimensional range/bearing innovation.

95% chi-square interval:

[0.0506, 7.3778]

NEES uses the 4-dimensional state error.

95% chi-square interval:

[0.4844, 11.1433]

Coverage is reported rather than assumed. A result outside nominal 95% coverage is treated as evidence of model/calibration mismatch, not hidden.

## Scenario

Each run contains:

- straight-motion segments
- two acceleration/maneuver segments
- Gaussian range/bearing noise
- deterministic observation dropouts
- identical truth/noise sequence for EKF and IMM comparisons

## Measured results

~~~text
ESTIMATION_VALIDATION phase=development model=EKF position_rmse_m=11.691 nees95_coverage=0.4769 nis95_coverage=0.9412 position_samples=4800 nees_samples=4800 nis_samples=4540
ESTIMATION_VALIDATION phase=development model=IMM position_rmse_m=10.430 nees95_coverage=0.8715 nis95_coverage=0.9498 position_samples=4800 nees_samples=4800 nis_samples=4540
ESTIMATION_VALIDATION phase=validation model=EKF position_rmse_m=11.723 nees95_coverage=0.4874 nis95_coverage=0.9388 position_samples=7200 nees_samples=7200 nis_samples=6810
ESTIMATION_VALIDATION phase=validation model=IMM position_rmse_m=10.439 nees95_coverage=0.8810 nis95_coverage=0.9438 position_samples=7200 nees_samples=7200 nis_samples=6810
~~~


## Interpretation

The held-out validation results show a clear improvement from the single-model EKF to the two-mode IMM.

The EKF achieved 11.723 m position RMSE on the validation seeds, while the IMM achieved 10.439 m. The IMM therefore improves maneuvering-target position accuracy without using the validation seeds for parameter selection.

Measurement-space consistency is close to the nominal 95% interval for both estimators:

- EKF NIS coverage: 93.88%
- IMM NIS coverage: 94.38%

State-space consistency exposes a larger difference:

- EKF NEES coverage: 48.74%
- IMM NEES coverage: 88.10%

The low EKF NEES coverage indicates that the constant-velocity filter is substantially overconfident when the target maneuvers: the true state falls outside the covariance-predicted region much more often than expected.

The IMM substantially reduces that inconsistency by assigning probability to a higher-process-noise maneuver mode. Its 88.10% NEES coverage is much closer to the nominal 95% target, although it remains below nominal and should not be described as perfectly calibrated.

These results support using the IMM as the stronger maneuvering-target estimator while preserving the simpler EKF as the frozen full-system baseline until a new end-to-end benchmark campaign is performed.

## Reproduction

~~~powershell
mvn -B -pl vanguard-tracking test "-Dtest=EstimationHardeningTest"
~~~

## Scope

The frozen full-system benchmark remains the existing EKF baseline so historical benchmark numbers are not silently invalidated.

The IMM is implemented and evaluated as an alternative estimator. The live tracking pipeline currently uses the Extended Kalman Filter; IMM results in this document come from controlled estimator evaluation rather than the production tracking path. Promoting IMM to the default full-system tracker would require a new frozen full-system benchmark campaign.
