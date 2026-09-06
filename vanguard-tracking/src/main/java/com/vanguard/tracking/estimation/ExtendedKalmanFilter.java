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

        SimpleMatrix K =
                P.mult(H.transpose())
                        .mult(S.invert());

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

        return y.transpose()
                .mult(S.invert())
                .mult(y)
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
        if (truthState.numRows() != 4 ||
                truthState.numCols() != 1) {
            throw new IllegalArgumentException(
                    "truthState must be 4x1"
            );
        }

        SimpleMatrix error =
                getState().minus(
                        truthState
                );

        return error.transpose()
                .mult(
                        getCovariance().invert()
                )
                .mult(error)
                .get(0, 0);
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
                state,
                P,
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
