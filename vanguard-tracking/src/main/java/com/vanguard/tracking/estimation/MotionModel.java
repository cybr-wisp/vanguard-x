package com.vanguard.tracking.estimation;

import org.ejml.simple.SimpleMatrix;

/**
 * Constant-velocity motion model in a local 2D Cartesian frame.
 *
 * State vector: x = [p_x, p_y, v_x, v_y]^T
 *
 * Transition:   x_{k|k-1} = F(dt) * x_{k-1}
 * Covariance:   P_{k|k-1} = F * P_{k-1} * F^T + Q(dt)
 *
 * Q uses a piecewise white-noise jerk model scaled by sigma_a^2
 * (unmodeled acceleration variance).
 */
public class MotionModel {

    private final double sigmaAccel; // process noise: acceleration std dev (m/s^2)

    /**
     * @param sigmaAccel standard deviation of unmodeled acceleration (m/s^2).
     *                   Higher = more responsive to maneuvers, noisier in straight flight.
     */
    public MotionModel(double sigmaAccel) {
        this.sigmaAccel = sigmaAccel;
    }

    /**
     * State transition matrix F(dt) for constant velocity.
     *
     *   | 1  0  dt  0 |
     *   | 0  1  0  dt |
     *   | 0  0  1   0 |
     *   | 0  0  0   1 |
     */
    public SimpleMatrix transitionMatrix(double dt) {
        return new SimpleMatrix(new double[][]{
                {1, 0, dt, 0},
                {0, 1, 0, dt},
                {0, 0, 1,  0},
                {0, 0, 0,  1}
        });
    }

    /**
     * Process noise covariance Q(dt). Uses the piecewise white-noise jerk
     * model (discretized continuous white noise acceleration):
     *
     *   q = sigma_a^2
     *
     *   Q = q * | dt^4/4   0       dt^3/2   0      |
     *           | 0        dt^4/4  0        dt^3/2  |
     *           | dt^3/2   0       dt^2     0       |
     *           | 0        dt^3/2  0        dt^2    |
     */
    public SimpleMatrix processNoise(double dt) {
        double q = sigmaAccel * sigmaAccel;
        double dt2 = dt * dt;
        double dt3 = dt2 * dt;
        double dt4 = dt3 * dt;

        return new SimpleMatrix(new double[][]{
                {dt4 / 4 * q, 0,            dt3 / 2 * q, 0           },
                {0,            dt4 / 4 * q, 0,            dt3 / 2 * q},
                {dt3 / 2 * q, 0,            dt2 * q,     0           },
                {0,            dt3 / 2 * q, 0,            dt2 * q    }
        });
    }

    /**
     * Predict the state forward by dt seconds.
     *
     * @param state current state [px, py, vx, vy]
     * @param dt    elapsed time in seconds (computed from per-track timestamps)
     * @return predicted state
     */
    public SimpleMatrix predict(SimpleMatrix state, double dt) {
        return transitionMatrix(dt).mult(state);
    }

    /**
     * Predict state and covariance forward.
     *
     * @return [predictedState, predictedCovariance]
     */
    public SimpleMatrix[] predictWithCovariance(SimpleMatrix state, SimpleMatrix P, double dt) {
        SimpleMatrix F = transitionMatrix(dt);
        SimpleMatrix Q = processNoise(dt);
        SimpleMatrix xPred = F.mult(state);
        SimpleMatrix pPred = F.mult(P).mult(F.transpose()).plus(Q);
        return new SimpleMatrix[]{xPred, pPred};
    }

    public double getSigmaAccel() { return sigmaAccel; }
}
