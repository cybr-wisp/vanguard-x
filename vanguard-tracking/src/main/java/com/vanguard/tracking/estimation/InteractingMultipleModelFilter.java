package com.vanguard.tracking.estimation;

import org.ejml.simple.SimpleMatrix;

import java.util.Arrays;

/**
 * Two-model Interacting Multiple Model estimator.
 *
 * Both modes use the common state [px, py, vx, vy]^T so state mixing is
 * exact and does not require dimension-changing transforms.
 *
 * Mode 0: low process noise, optimized for near-constant velocity.
 * Mode 1: high process noise, optimized for maneuver response.
 *
 * The IMM performs:
 *   1. mode-probability prediction
 *   2. state/covariance mixing
 *   3. per-model EKF prediction
 *   4. measurement-likelihood update
 *   5. posterior mode-probability normalization
 *   6. Gaussian-mixture moment matching for the combined estimate
 */
public class InteractingMultipleModelFilter
        extends ExtendedKalmanFilter {

    private static final int MODE_COUNT = 2;

    private final MotionModel[] motionModels;
    private ExtendedKalmanFilter[] models;

    /*
     * transition[i][j] =
     * P(mode j at k | mode i at k-1)
     */
    private final double[][] transition = {
            {0.97, 0.03},
            {0.08, 0.92}
    };

    private double[] modeProbabilities = {
            0.80,
            0.20
    };

    public InteractingMultipleModelFilter(
            SimpleMatrix initialState,
            SimpleMatrix initialCovariance,
            double nominalSigmaAccel,
            double maneuverSigmaAccel
    ) {
        super(
                initialState,
                initialCovariance,
                new MotionModel(
                        nominalSigmaAccel
                )
        );

        if (nominalSigmaAccel <= 0 ||
                maneuverSigmaAccel <= nominalSigmaAccel) {
            throw new IllegalArgumentException(
                    "require 0 < nominalSigmaAccel < maneuverSigmaAccel"
            );
        }

        motionModels =
                new MotionModel[]{
                        new MotionModel(
                                nominalSigmaAccel
                        ),
                        new MotionModel(
                                maneuverSigmaAccel
                        )
                };

        models =
                new ExtendedKalmanFilter[]{
                        new ExtendedKalmanFilter(
                                initialState,
                                initialCovariance,
                                motionModels[0]
                        ),
                        new ExtendedKalmanFilter(
                                initialState,
                                initialCovariance,
                                motionModels[1]
                        )
                };
    }

    @Override
    public void predict(double dt) {
        if (dt <= 0) {
            return;
        }

        double[] predictedModeProbability =
                new double[MODE_COUNT];

        for (int j = 0; j < MODE_COUNT; j++) {
            for (int i = 0; i < MODE_COUNT; i++) {
                predictedModeProbability[j] +=
                        transition[i][j] *
                                modeProbabilities[i];
            }

            predictedModeProbability[j] =
                    Math.max(
                            predictedModeProbability[j],
                            1e-15
                    );
        }

        ExtendedKalmanFilter[] mixedModels =
                new ExtendedKalmanFilter[
                        MODE_COUNT
                        ];

        for (int destination = 0;
             destination < MODE_COUNT;
             destination++) {

            double[] mixingWeights =
                    new double[MODE_COUNT];

            for (int source = 0;
                 source < MODE_COUNT;
                 source++) {

                mixingWeights[source] =
                        transition[source][destination] *
                                modeProbabilities[source] /
                                predictedModeProbability[
                                        destination
                                        ];
            }

            SimpleMatrix mixedState =
                    new SimpleMatrix(4, 1);

            for (int source = 0;
                 source < MODE_COUNT;
                 source++) {

                mixedState =
                        mixedState.plus(
                                models[source]
                                        .getState()
                                        .scale(
                                                mixingWeights[
                                                        source
                                                        ]
                                        )
                        );
            }

            SimpleMatrix mixedCovariance =
                    new SimpleMatrix(4, 4);

            for (int source = 0;
                 source < MODE_COUNT;
                 source++) {

                SimpleMatrix difference =
                        models[source]
                                .getState()
                                .minus(
                                        mixedState
                                );

                SimpleMatrix component =
                        models[source]
                                .getCovariance()
                                .plus(
                                        difference.mult(
                                                difference.transpose()
                                        )
                                );

                mixedCovariance =
                        mixedCovariance.plus(
                                component.scale(
                                        mixingWeights[
                                                source
                                                ]
                                )
                        );
            }

            mixedModels[destination] =
                    new ExtendedKalmanFilter(
                            mixedState,
                            mixedCovariance,
                            motionModels[
                                    destination
                                    ]
                    );

            mixedModels[destination]
                    .predict(dt);
        }

        models = mixedModels;

        modeProbabilities =
                predictedModeProbability;

        normalizeModeProbabilities();
    }

    @Override
    public void update(
            SimpleMatrix measurement,
            MeasurementModel sensorModel
    ) {
        double[] logWeights =
                new double[MODE_COUNT];

        for (int i = 0;
             i < MODE_COUNT;
             i++) {

            SimpleMatrix[] innovation =
                    models[i]
                            .computeInnovation(
                                    measurement,
                                    sensorModel
                            );

            SimpleMatrix y =
                    innovation[0];

            SimpleMatrix S =
                    innovation[1];

            double logPrior =
                    Math.log(
                            Math.max(
                                    modeProbabilities[i],
                                    1e-300
                            )
                    );

            double determinant =
                    S.determinant();

            if (!Double.isFinite(determinant)
                    || determinant <= 0.0) {

                logWeights[i] =
                        logPrior - 1e12;

                continue;
            }

            final double quadratic;

            try {
                SimpleMatrix solved =
                        solveChecked(
                                S,
                                y,
                                "IMM innovation covariance"
                        );

                quadratic =
                        y.transpose()
                                .mult(solved)
                                .get(0, 0);
            } catch (IllegalStateException ex) {
                logWeights[i] =
                        logPrior - 1e12;

                continue;
            }

            if (!Double.isFinite(quadratic)
                    || quadratic < 0.0) {

                logWeights[i] =
                        logPrior - 1e12;

                continue;
            }

            double logLikelihood =
                    -0.5 * (
                            quadratic +
                                    Math.log(
                                            determinant
                                    ) +
                                    2.0 *
                                            Math.log(
                                                    2.0 *
                                                            Math.PI
                                            )
                    );

            logWeights[i] =
                    Math.log(
                            Math.max(
                                    modeProbabilities[i],
                                    1e-300
                            )
                    ) +
                            logLikelihood;

            models[i].update(
                    measurement,
                    sensorModel
            );
        }

        double maxLog =
                Math.max(
                        logWeights[0],
                        logWeights[1]
                );

        for (int i = 0;
             i < MODE_COUNT;
             i++) {

            modeProbabilities[i] =
                    Math.exp(
                            logWeights[i] -
                                    maxLog
                    );
        }

        normalizeModeProbabilities();
    }

    @Override
    public SimpleMatrix[] computeInnovation(
            SimpleMatrix measurement,
            MeasurementModel sensorModel
    ) {
        SimpleMatrix state =
                getState();

        SimpleMatrix covariance =
                getCovariance();

        SimpleMatrix hx =
                sensorModel.h(state);

        SimpleMatrix H =
                sensorModel.jacobian(state);

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
                H.mult(covariance)
                        .mult(H.transpose())
                        .plus(
                                sensorModel
                                        .noiseCovariance()
                        );

        return new SimpleMatrix[]{
                innovation,
                S
        };
    }

    @Override
    public SimpleMatrix getState() {
        SimpleMatrix combined =
                new SimpleMatrix(4, 1);

        for (int i = 0;
             i < MODE_COUNT;
             i++) {

            combined =
                    combined.plus(
                            models[i]
                                    .getState()
                                    .scale(
                                            modeProbabilities[i]
                                    )
                    );
        }

        return combined;
    }

    @Override
    public SimpleMatrix getCovariance() {
        SimpleMatrix combinedState =
                getState();

        SimpleMatrix combined =
                new SimpleMatrix(4, 4);

        for (int i = 0;
             i < MODE_COUNT;
             i++) {

            SimpleMatrix difference =
                    models[i]
                            .getState()
                            .minus(
                                    combinedState
                            );

            SimpleMatrix component =
                    models[i]
                            .getCovariance()
                            .plus(
                                    difference.mult(
                                            difference.transpose()
                                    )
                            );

            combined =
                    combined.plus(
                            component.scale(
                                    modeProbabilities[i]
                            )
                    );
        }

        return combined.plus(
                combined.transpose()
        ).scale(0.5);
    }

    @Override
    public double getPx() {
        return getState().get(0, 0);
    }

    @Override
    public double getPy() {
        return getState().get(1, 0);
    }

    @Override
    public double getVx() {
        return getState().get(2, 0);
    }

    @Override
    public double getVy() {
        return getState().get(3, 0);
    }

    @Override
    public double getPositionUncertainty() {
        SimpleMatrix covariance =
                getCovariance();

        return Math.sqrt(
                Math.max(
                        0.0,
                        covariance.get(0, 0) +
                                covariance.get(1, 1)
                )
        );
    }

    public double[] getModeProbabilities() {
        return Arrays.copyOf(
                modeProbabilities,
                modeProbabilities.length
        );
    }

    private void normalizeModeProbabilities() {
        double sum =
                modeProbabilities[0] +
                        modeProbabilities[1];

        if (!Double.isFinite(sum) ||
                sum <= 0) {

            modeProbabilities =
                    new double[]{
                            0.5,
                            0.5
                    };

            return;
        }

        modeProbabilities[0] /= sum;
        modeProbabilities[1] /= sum;
    }
}
