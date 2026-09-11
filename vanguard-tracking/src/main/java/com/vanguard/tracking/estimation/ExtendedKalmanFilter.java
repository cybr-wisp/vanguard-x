package com.vanguard.tracking.estimation;

import org.ejml.simple.SimpleMatrix;

/**
 * Extended Kalman Filter for 2D constant-velocity tracking with nonlinear
 * range/bearing measurements.
 *
 * State: [px, py, vx, vy]^T.
 *
 * Covariance updates use the Joseph stabilized form:
 *
 * P = (I - KH) P (I - KH)^T + K R K^T
 *
 * which is more robust to finite-precision loss of symmetry and
 * positive-semidefiniteness than the simplified (I - KH)P form.
 */
public class ExtendedKalmanFilter {

    private SimpleMatrix state;
    private SimpleMatrix P;
    private final MotionModel motionModel;

    public ExtendedKalmanFilter(
            SimpleMatrix initialState,
            SimpleMatrix initialP,
            MotionModel motionModel
    ) {
        this.state = initialState.copy();
        this.P = initialP.copy();
        this.motionModel = motionModel;
    }

    /**
     * Predict state and covariance forward by dt seconds.
     */
    public void predict(double dt) {
        if (dt <= 0) {
            return;
        }

        SimpleMatrix[] predicted =
                motionModel.predictWithCovariance(
                        state,
                        P,
                        dt
                );

        state = predicted[0];
        P = predicted[1];

        enforceSymmetry();
    }

    /**
     * EKF range/bearing measurement update.
     */
    public void update(
            SimpleMatrix measurement,
            MeasurementModel sensorModel
    ) {
        SimpleMatrix hx =
                sensorModel.h(state);

        SimpleMatrix H =
                sensorModel.jacobian(state);

        SimpleMatrix R =
                sensorModel.noiseCovariance();

        SimpleMatrix innovation =
                measurement.minus(hx);

        innovation.set(
                1,
                0,
                MeasurementModel.normalizeBearing(
                        innovation.get(1, 0)
                )
        );

        SimpleMatrix S =
                H.mult(P)
                        .mult(H.transpose())
                        .plus(R);

        SimpleMatrix pHt =
                P.mult(H.transpose());

        /*
         * Solve S * X = (P H^T)^T rather than explicitly forming S^-1.
         * Since S is symmetric, K = X^T.
         */
        SimpleMatrix K =
                solveChecked(
                        S,
                        pHt.transpose(),
                        "innovation covariance"
                ).transpose();

        state =
                state.plus(
                        K.mult(innovation)
                );

        /*
         * Joseph stabilized covariance update:
         *
         * P = (I-KH)P(I-KH)^T + KRK^T
         */
        SimpleMatrix identity =
                SimpleMatrix.identity(4);

        SimpleMatrix iMinusKh =
                identity.minus(
                        K.mult(H)
                );

        P =
                iMinusKh
                        .mult(P)
                        .mult(iMinusKh.transpose())
                        .plus(
                                K.mult(R)
                                        .mult(K.transpose())
                        );

        enforceSymmetry();
    }

    /**
     * Innovation and innovation covariance without modifying filter state.
     *
     * @return [innovation y, innovation covariance S]
     */
    public SimpleMatrix[] computeInnovation(
            SimpleMatrix measurement,
            MeasurementModel sensorModel
    ) {
        SimpleMatrix hx =
                sensorModel.h(state);

        SimpleMatrix H =
                sensorModel.jacobian(state);

        SimpleMatrix R =
                sensorModel.noiseCovariance();

        SimpleMatrix innovation =
                measurement.minus(hx);

        innovation.set(
                1,
                0,
                MeasurementModel.normalizeBearing(
                        innovation.get(1, 0)
                )
        );

        SimpleMatrix S =
                H.mult(P)
                        .mult(H.transpose())
                        .plus(R);

        return new SimpleMatrix[]{
                innovation,
                S
        };
    }

    /**
     * Normalized Innovation Squared.
     *
     * For the 2D [range,bearing] measurement, NIS has two degrees
     * of freedom under the correctly specified Gaussian model.
     */
    public double nis(
            SimpleMatrix measurement,
            MeasurementModel sensorModel
    ) {
        SimpleMatrix[] innovation =
                computeInnovation(
                        measurement,
                        sensorModel
                );

        SimpleMatrix y =
                innovation[0];

        SimpleMatrix S =
                innovation[1];

        SimpleMatrix solved =
                solveChecked(
                        S,
                        y,
                        "innovation covariance"
                );

        return y.transpose()
                .mult(solved)
                .get(0, 0);
    }

    /**
     * Normalized Estimation Error Squared.
     *
     * This method requires ground truth and is therefore intended only
     * for simulation/evaluation, never runtime association.
     */
    public double nees(
            SimpleMatrix truthState
    ) {
        if (truthState.getNumRows() != 4 ||
                truthState.getNumCols() != 1) {
            throw new IllegalArgumentException(
                    "truthState must be 4x1"
            );
        }

        SimpleMatrix error =
                getState().minus(
                        truthState
                );

        SimpleMatrix covariance =
                getCovariance();

        SimpleMatrix solved =
                solveChecked(
                        covariance,
                        error,
                        "state covariance"
                );

        return error.transpose()
                .mult(solved)
                .get(0, 0);
    }
    /**
     * Solve A X = B without explicitly forming A^-1.
     *
     * Covariance matrices used here are expected to be positive definite.
     * Fail explicitly if the matrix or resulting solution is numerically
     * invalid rather than propagating NaN/Inf state through the tracker.
     */
    protected static SimpleMatrix solveChecked(
            SimpleMatrix matrix,
            SimpleMatrix rhs,
            String context
    ) {
        double determinant =
                matrix.determinant();

        if (!Double.isFinite(determinant)
                || determinant <= 0.0) {
            throw new IllegalStateException(
                    context +
                            " is singular or not positive definite"
            );
        }

        final SimpleMatrix solution;

        try {
            solution =
                    matrix.solve(rhs);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Failed to solve " + context,
                    ex
            );
        }

        for (int row = 0;
             row < solution.getNumRows();
             row++) {

            for (int col = 0;
                 col < solution.getNumCols();
                 col++) {

                if (!Double.isFinite(
                        solution.get(row, col)
                )) {
                    throw new IllegalStateException(
                            context +
                                    " solve produced non-finite values"
                    );
                }
            }
        }

        return solution;
    }

    private void enforceSymmetry() {
        P =
                P.plus(
                        P.transpose()
                ).scale(0.5);
    }

    /**
     * Immutable-at-creation filter snapshot for same-timestamp association.
     *
     * The constructor performs the defensive copies exactly once.
     */
    public ExtendedKalmanFilter snapshot() {
        return new ExtendedKalmanFilter(
                getState(),
                getCovariance(),
                motionModel
        );
    }
    public SimpleMatrix getState() {
        return state.copy();
    }

    public SimpleMatrix getCovariance() {
        return P.copy();
    }

    public double getPx() {
        return state.get(0, 0);
    }

    public double getPy() {
        return state.get(1, 0);
    }

    public double getVx() {
        return state.get(2, 0);
    }

    public double getVy() {
        return state.get(3, 0);
    }

    public double getPositionUncertainty() {
        return Math.sqrt(
                Math.max(
                        0.0,
                        P.get(0, 0) +
                                P.get(1, 1)
                )
        );
    }
}
