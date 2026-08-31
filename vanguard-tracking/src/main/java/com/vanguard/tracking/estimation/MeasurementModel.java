package com.vanguard.tracking.estimation;

import org.ejml.simple.SimpleMatrix;

/**
 * Nonlinear measurement model for a sensor at position (sx, sy).
 *
 * The sensor reports range r and bearing theta:
 *   r     = sqrt((px - sx)^2 + (py - sy)^2)
 *   theta = atan2(py - sy, px - sx)
 *
 * This mapping is nonlinear (sqrt, atan2), which is why the EKF is
 * a real engineering requirement rather than a buzzword.
 *
 * The EKF linearizes h(x) around the predicted state via the Jacobian H.
 */
public class MeasurementModel {

    private final double sx;  // sensor x position
    private final double sy;  // sensor y position
    private final SimpleMatrix R; // 2x2 measurement noise covariance

    /**
     * @param sx             sensor x position (m)
     * @param sy             sensor y position (m)
     * @param sigmaRange     range measurement noise std dev (m)
     * @param sigmaBearing   bearing measurement noise std dev (rad)
     */
    public MeasurementModel(double sx, double sy, double sigmaRange, double sigmaBearing) {
        this.sx = sx;
        this.sy = sy;
        this.R = new SimpleMatrix(new double[][]{
                {sigmaRange * sigmaRange, 0},
                {0, sigmaBearing * sigmaBearing}
        });
    }

    /**
     * Predicted measurement h(x): what the sensor expects to see given
     * the current predicted state.
     *
     * @param state predicted state [px, py, vx, vy]^T (4x1)
     * @return [range, bearing]^T (2x1)
     */
    public SimpleMatrix h(SimpleMatrix state) {
        double dx = state.get(0) - sx;
        double dy = state.get(1) - sy;
        double range   = Math.sqrt(dx * dx + dy * dy);
        double bearing = Math.atan2(dy, dx);
        return new SimpleMatrix(new double[][]{{range}, {bearing}});
    }

    /**
     * Jacobian of h(x) evaluated at the current predicted state.
     *
     *   H = | dx/r      dy/r      0  0 |
     *       | -dy/r^2   dx/r^2    0  0 |
     *
     * where dx = px - sx, dy = py - sy, r = sqrt(dx^2 + dy^2).
     *
     * @param state predicted state [px, py, vx, vy]^T (4x1)
     * @return Jacobian H (2x4)
     */
    public SimpleMatrix jacobian(SimpleMatrix state) {
        double dx = state.get(0) - sx;
        double dy = state.get(1) - sy;
        double r  = Math.sqrt(dx * dx + dy * dy);

        if (r < 1e-10) {
            // Target is essentially co-located with sensor. Return a safe
            // near-zero Jacobian rather than dividing by zero.
            return new SimpleMatrix(2, 4);
        }

        double r2 = r * r;
        return new SimpleMatrix(new double[][]{
                { dx / r,    dy / r,   0, 0},
                {-dy / r2,   dx / r2,  0, 0}
        });
    }

    /**
     * Measurement noise covariance R. Sensor-specific so one noisy sensor
     * contributes less confidence than a precise one.
     */
    public SimpleMatrix noiseCovariance() {
        return R;
    }

    public double getSx() { return sx; }
    public double getSy() { return sy; }

    /**
     * Normalize a bearing residual to [-pi, pi]. Critical for avoiding
     * massive innovation values near the +/-pi boundary.
     */
    public static double normalizeBearing(double angle) {
        while (angle >  Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
}
