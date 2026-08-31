package com.vanguard.tracking.estimation;

import org.ejml.simple.SimpleMatrix;

/**
 * Extended Kalman Filter for 2D constant-velocity tracking with nonlinear
 * range/bearing measurements.
 *
 * The filter maintains a 4-element state [px, py, vx, vy] and a 4x4
 * covariance matrix P. It supports:
 *
 *   - Predict: advance state and covariance by dt seconds using the
 *     constant-velocity motion model.
 *   - Update: incorporate a noisy range/bearing observation from a
 *     sensor at a known position.
 *
 * Delta-t is computed per-track from timestamps, not assumed fixed.
 * Bearing residuals are normalized to [-pi, pi] before use.
 */
public class ExtendedKalmanFilter {

    private SimpleMatrix state; // 4x1 [px, py, vx, vy]
    private SimpleMatrix P;     // 4x4 covariance
    private final MotionModel motionModel;

    /**
     * Initialize the filter.
     *
     * @param initialState 4x1 [px, py, vx, vy]
     * @param initialP     4x4 initial covariance (large diagonal = high uncertainty)
     * @param motionModel  constant-velocity motion model with process noise
     */
    public ExtendedKalmanFilter(SimpleMatrix initialState, SimpleMatrix initialP,
                                 MotionModel motionModel) {
        this.state = initialState.copy();
        this.P = initialP.copy();
        this.motionModel = motionModel;
    }

    /**
     * Predict step: advance state and covariance by dt seconds.
     * Covariance grows during prediction (uncertainty increases without observations).
     *
     * @param dt elapsed time in seconds since last update
     */
    public void predict(double dt) {
        if (dt <= 0) return; // no-op for zero or negative dt

        SimpleMatrix[] predicted = motionModel.predictWithCovariance(state, P, dt);
        state = predicted[0];
        P     = predicted[1];
    }

    /**
     * Update step: incorporate one range/bearing observation from a sensor.
     *
     * Steps:
     *   1. Compute predicted measurement h(x) and Jacobian H
     *   2. Innovation y = z - h(x), with bearing residual normalized
     *   3. Innovation covariance S = H * P * H^T + R
     *   4. Kalman gain K = P * H^T * S^{-1}
     *   5. State update: x = x + K * y
     *   6. Covariance update: P = (I - K*H) * P
     *
     * @param measurement    2x1 [range, bearing] observation
     * @param sensorModel    measurement model for the observing sensor
     */
    public void update(SimpleMatrix measurement, MeasurementModel sensorModel) {
        // 1. Predicted measurement and Jacobian
        SimpleMatrix hx = sensorModel.h(state);
        SimpleMatrix H  = sensorModel.jacobian(state);
        SimpleMatrix R  = sensorModel.noiseCovariance();

        // 2. Innovation with bearing normalization
        SimpleMatrix y = measurement.minus(hx);
        y.set(1, 0, MeasurementModel.normalizeBearing(y.get(1, 0)));

        // 3. Innovation covariance
        SimpleMatrix S = H.mult(P).mult(H.transpose()).plus(R);

        // 4. Kalman gain
        SimpleMatrix K = P.mult(H.transpose()).mult(S.invert());

        // 5. State update
        state = state.plus(K.mult(y));

        // 6. Covariance update: P = (I - K*H) * P
        SimpleMatrix I4 = SimpleMatrix.identity(4);
        P = I4.minus(K.mult(H)).mult(P);

        // Enforce symmetry (numerical stability)
        P = P.plus(P.transpose()).scale(0.5);
    }

    /**
     * Compute the innovation and innovation covariance S for a candidate
     * measurement. Used by Mahalanobis gating (Day 8) to decide association
     * without modifying the filter state.
     *
     * @return [innovation (2x1), S (2x2)]
     */
    public SimpleMatrix[] computeInnovation(SimpleMatrix measurement, MeasurementModel sensorModel) {
        SimpleMatrix hx = sensorModel.h(state);
        SimpleMatrix H  = sensorModel.jacobian(state);
        SimpleMatrix R  = sensorModel.noiseCovariance();

        SimpleMatrix y = measurement.minus(hx);
        y.set(1, 0, MeasurementModel.normalizeBearing(y.get(1, 0)));

        SimpleMatrix S = H.mult(P).mult(H.transpose()).plus(R);
        return new SimpleMatrix[]{y, S};
    }

    // ---- Accessors ----

    /** Current estimated state [px, py, vx, vy]. */
    public SimpleMatrix getState() { return state.copy(); }

    /** Current covariance matrix (4x4). */
    public SimpleMatrix getCovariance() { return P.copy(); }

    public double getPx() { return state.get(0); }
    public double getPy() { return state.get(1); }
    public double getVx() { return state.get(2); }
    public double getVy() { return state.get(3); }

    /** Position uncertainty: sqrt of the trace of the position block of P. */
    public double getPositionUncertainty() {
        return Math.sqrt(P.get(0, 0) + P.get(1, 1));
    }
}
