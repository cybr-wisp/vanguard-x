# Extended Kalman Filter - Mathematical Specification

## State Vector

```
x = [px, py, vx, vy]^T
```

- `px, py`: position in meters (projected from lat/lon)
- `vx, vy`: velocity in m/s

## State Transition (Prediction)

```
x_pred = F * x_prev

F = | 1  0  dt  0 |
    | 0  1  0  dt |
    | 0  0  1   0 |
    | 0  0  0   1 |
```

## Measurement Model

```
z = [px_meas, py_meas]^T

H = | 1  0  0  0 |
    | 0  1  0  0 |
```

## Covariance

<!-- TODO (Day 5-6): Fill in Q, R matrices and tuning rationale -->

Process noise `Q` and measurement noise `R` are configured in `application.yml` under
`vanguard.filtering.ekf`.

## Gate Check

If the Mahalanobis distance between the predicted and measured position exceeds a
threshold, the measurement is flagged as a potential new track or false alarm.

<!-- TODO: Document the gating threshold and how it was chosen -->
