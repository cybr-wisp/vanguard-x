# State Estimation in Vanguard

## Problem statement

Three imperfect sensors observe the same moving targets. Reports arrive
asynchronously and may be noisy, delayed, duplicated, reordered, or lost.
Vanguard must infer a shared canonical state for each target from these
degraded observations.

## State vector

Each canonical track is represented in a local 2D Cartesian frame:

    x_k = [p_x, p_y, v_x, v_y]^T

where (p_x, p_y) is position in meters and (v_x, v_y) is velocity in m/s.

## Motion model (constant velocity)

Between measurements separated by dt seconds:

    x_{k|k-1} = F(dt) * x_{k-1|k-1}

where:

    F(dt) = | 1  0  dt  0 |
            | 0  1  0  dt |
            | 0  0  1   0 |
            | 0  0  0   1 |

The covariance prediction is:

    P_{k|k-1} = F * P_{k-1|k-1} * F^T + Q

Q models unmodeled acceleration (process noise). Larger Q makes the filter
more responsive to maneuvers but noisier during straight-line motion.

**Key implementation note:** delta-t is computed per-track from the elapsed
time since the last update, not assumed to be a fixed step. Sensor reports
are asynchronous.

## Nonlinear measurement model

Sensor j at position (s_x, s_y) reports range r and bearing theta:

    r     = sqrt((p_x - s_x)^2 + (p_y - s_y)^2)
    theta = atan2(p_y - s_y, p_x - s_x)

This mapping is nonlinear (square root, atan2), which is why a standard
Kalman filter is insufficient and the Extended Kalman Filter is a real
engineering requirement rather than a buzzword.

## Jacobian of the measurement model

The EKF linearizes h(x) around the current predicted state. Define:

    d_x = p_x - s_x
    d_y = p_y - s_y

The Jacobian H_j is:

    H_j = | d_x/r      d_y/r      0  0 |
          | -d_y/r^2    d_x/r^2    0  0 |

## Innovation and update

The innovation (measurement residual):

    y_k = z_k - h(x_{k|k-1})

Innovation covariance:

    S_k = H_k * P_{k|k-1} * H_k^T + R_k

where R_k is the sensor-specific measurement noise covariance. Each sensor
has its own R, so a noisy sensor contributes less confidence than a precise one.

Kalman gain:

    K_k = P_{k|k-1} * H_k^T * S_k^{-1}

State and covariance update:

    x_{k|k} = x_{k|k-1} + K_k * y_k
    P_{k|k} = (I - K_k * H_k) * P_{k|k-1}

**Bearing normalization:** the bearing residual must be normalized to [-pi, pi]
before computing the innovation. Without this, a small angular difference
near the +/- pi boundary produces a massive residual.

## Covariance interpretation

Covariance is not just "accuracy." Geometrically, the covariance ellipse
shows the region of uncertainty around the estimated position. A larger
covariance means the track is less certain, not merely less accurate.

During coasting (no observations), the covariance grows with each prediction
step. This is correct behavior: the filter is honestly reporting that it
knows less about a track it has not observed recently.

## Why range/bearing observations matter

If the simulator emitted clean Cartesian (x, y) positions, the observation
model would be linear and a standard Kalman filter would suffice. Using
range/bearing forces the nonlinear measurement model and makes the EKF
a genuine engineering requirement. This is the difference between a
project that name-drops Kalman filtering and one that actually needs it.

## Trajectory model

Ground-truth trajectories are composed of three segment types:

1. **Straight** (constant velocity): position advances linearly
2. **Acceleration** (constant acceleration): velocity ramps linearly
3. **Coordinated turn** (constant turn rate): speed is preserved,
   heading changes at omega rad/s

Segments are contiguous: the end state of one segment is the initial
state of the next. The builder enforces this invariant.

## Evaluation

Because the simulator owns ground truth, estimation quality is measurable:

- Position RMSE: sqrt(mean((p_hat - p_true)^2))
- Velocity RMSE: same for velocity components
- Association accuracy: fraction of reports assigned to the correct target
- Track fragmentation: one truth target mapped to multiple canonical tracks
- False-track rate: tracks created from clutter
