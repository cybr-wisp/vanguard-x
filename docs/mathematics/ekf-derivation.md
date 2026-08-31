# EKF Derivation for Vanguard

## Why the Extended Kalman Filter

A standard Kalman filter requires linear observation and motion models.
Vanguard's sensors report **range and bearing**, not Cartesian positions.
The mapping from state to measurement contains `sqrt` and `atan2`, which
are nonlinear. The EKF linearizes this mapping at each step via the
Jacobian, making it a genuine engineering requirement rather than a
name-drop.

## Notation

    x   = state vector [px, py, vx, vy]^T       (4x1)
    P   = state covariance                       (4x4)
    z   = measurement [range, bearing]^T          (2x1)
    h(x) = nonlinear measurement function          (2x1)
    H   = Jacobian of h evaluated at predicted x  (2x4)
    R   = measurement noise covariance            (2x2, sensor-specific)
    F   = state transition matrix                 (4x4)
    Q   = process noise covariance                (4x4)

## Predict step

Given elapsed time dt since the last update (per-track, not global):

    x_{k|k-1} = F(dt) * x_{k-1|k-1}
    P_{k|k-1} = F * P_{k-1|k-1} * F^T + Q(dt)

F is the constant-velocity transition matrix:

    F(dt) = | 1  0  dt  0 |
            | 0  1  0  dt |
            | 0  0  1   0 |
            | 0  0  0   1 |

Q uses a discretized continuous white-noise acceleration model
parameterized by sigma_a (unmodeled acceleration standard deviation):

    q = sigma_a^2

    Q = q * | dt^4/4   0       dt^3/2   0      |
            | 0        dt^4/4  0        dt^3/2  |
            | dt^3/2   0       dt^2     0       |
            | 0        dt^3/2  0        dt^2    |

Larger sigma_a makes the filter more responsive to maneuvers but noisier
during straight-line motion.

## Measurement function h(x)

For a sensor at position (s_x, s_y):

    d_x = p_x - s_x
    d_y = p_y - s_y

    h(x) = | sqrt(d_x^2 + d_y^2)  |    (range)
           | atan2(d_y, d_x)       |    (bearing)

## Jacobian H

The partial derivatives of h with respect to x:

    H = | d_x/r      d_y/r      0  0 |
        | -d_y/r^2   d_x/r^2    0  0 |

where r = sqrt(d_x^2 + d_y^2).

The velocity columns are zero because velocity does not appear in the
measurement model. This means a single observation cannot directly constrain
velocity; the filter infers velocity from successive position updates.

**Verification:** the analytical Jacobian is tested against numerical
finite differences in `EstimationTest.jacobianMatchesNumerical`.

## Update step

1. **Innovation** (measurement residual):

       y = z - h(x_{k|k-1})

   The bearing component of y MUST be normalized to [-pi, pi]. Without
   this, a small angular difference near the +/- pi boundary produces a
   massive residual that corrupts the estimate.

2. **Innovation covariance:**

       S = H * P_{k|k-1} * H^T + R

   S combines the predicted state uncertainty with the sensor's own
   measurement noise. A noisier sensor (larger R) produces a larger S,
   which reduces the Kalman gain.

3. **Kalman gain:**

       K = P_{k|k-1} * H^T * S^{-1}

   K determines how strongly the new observation pulls the estimate.
   When the filter is very uncertain (large P), K is large and the
   observation dominates. When the filter is confident, K is small and
   the observation has less effect.

4. **State update:**

       x_{k|k} = x_{k|k-1} + K * y

5. **Covariance update:**

       P_{k|k} = (I - K * H) * P_{k|k-1}

   After update, P is symmetrized: P = (P + P^T) / 2 to prevent
   numerical drift.

## Sensor-specific R

Each sensor has its own measurement noise covariance:

    R = | sigma_range^2    0              |
        | 0                sigma_bearing^2 |

A noisy sensor contributes less confidence per observation because its
larger R inflates the innovation covariance S and shrinks the Kalman gain K.
This means the EKF automatically weights sensors by quality without any
explicit logic.

## Coasting behavior

During coasting (no observations received), only the predict step runs.
Each prediction increases covariance because Q is added. This is correct:
the filter honestly reports that it knows less about a track it has not
observed recently. The growing covariance is visible as a larger uncertainty
ellipse in the UI.

## Implementation notes

All matrix operations use EJML (`SimpleMatrix`). No external tracking
framework is imported. The equations above map directly to the code in
`MotionModel.java`, `MeasurementModel.java`, and `ExtendedKalmanFilter.java`.

The `computeInnovation` method calculates innovation and S without
modifying filter state. This supports Mahalanobis gating (Day 8): the
tracker can test a candidate observation against a track's predicted
state before committing to an update.
