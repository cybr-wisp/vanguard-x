package com.vanguard.tracking.estimation;

import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EstimationHardeningTest {

    private static final long DEV_SEED_START =
            20267000L;

    private static final long DEV_SEED_END =
            20267019L;

    private static final long VALIDATION_SEED_START =
            20268000L;

    private static final long VALIDATION_SEED_END =
            20268029L;

    /*
     * 95% chi-square consistency intervals.
     *
     * NIS:  2 measurement dimensions.
     * NEES: 4 state dimensions.
     */
    private static final double NIS_95_LOW =
            0.0506;

    private static final double NIS_95_HIGH =
            7.3778;

    private static final double NEES_95_LOW =
            0.4844;

    private static final double NEES_95_HIGH =
            11.1433;

    @Test
    void josephCovarianceRemainsFiniteSymmetricAndNonnegative()
            throws Exception {

        MotionModel motion =
                new MotionModel(2.0);

        MeasurementModel sensor =
                new MeasurementModel(
                        0.0,
                        0.0,
                        30.0,
                        0.008
                );

        ExtendedKalmanFilter filter =
                new ExtendedKalmanFilter(
                        new SimpleMatrix(
                                new double[][]{
                                        {900.0},
                                        {450.0},
                                        {20.0},
                                        {3.0}
                                }
                        ),
                        SimpleMatrix
                                .identity(4)
                                .scale(5000.0),
                        motion
                );

        Random random =
                new Random(20260906L);

        double px = 1000.0;
        double py = 500.0;
        double vx = 25.0;
        double vy = 4.0;
        double dt = 0.1;

        for (int step = 0;
             step < 1000;
             step++) {

            px += vx * dt;
            py += vy * dt;

            filter.predict(dt);

            if (step % 17 != 0) {

                SimpleMatrix truth =
                        state(
                                px,
                                py,
                                vx,
                                vy
                        );

                SimpleMatrix measurement =
                        noisyMeasurement(
                                sensor,
                                truth,
                                random,
                                30.0,
                                0.008
                        );

                filter.update(
                        measurement,
                        sensor
                );
            }

            SimpleMatrix covariance =
                    filter.getCovariance();

            for (int row = 0;
                 row < 4;
                 row++) {

                assertTrue(
                        Double.isFinite(
                                covariance.get(
                                        row,
                                        row
                                )
                        ),
                        "covariance diagonal must remain finite"
                );

                assertTrue(
                        covariance.get(
                                row,
                                row
                        ) >= -1e-9,
                        "covariance diagonal must remain non-negative"
                );

                for (int column = 0;
                     column < 4;
                     column++) {

                    assertTrue(
                            Double.isFinite(
                                    covariance.get(
                                            row,
                                            column
                                    )
                            ),
                            "all covariance entries must remain finite"
                    );

                    assertEquals(
                            covariance.get(
                                    row,
                                    column
                            ),
                            covariance.get(
                                    column,
                                    row
                            ),
                            1e-8,
                            "Joseph covariance must remain symmetric"
                    );
                }
            }
        }
    }

    @Test
    void immModeProbabilitiesRemainNormalized()
            throws Exception {

        MeasurementModel sensor =
                new MeasurementModel(
                        0.0,
                        0.0,
                        20.0,
                        0.005
                );

        InteractingMultipleModelFilter imm =
                new InteractingMultipleModelFilter(
                        state(
                                1000.0,
                                500.0,
                                25.0,
                                5.0
                        ),
                        SimpleMatrix
                                .identity(4)
                                .scale(1000.0),
                        1.0,
                        8.0
                );

        Random random =
                new Random(77L);

        double px = 1000.0;
        double py = 500.0;
        double vx = 25.0;
        double vy = 5.0;
        double dt = 0.1;

        for (int step = 0;
             step < 250;
             step++) {

            double ax =
                    step >= 80 &&
                            step < 130
                            ? 3.0
                            : 0.0;

            double ay =
                    step >= 80 &&
                            step < 130
                            ? -1.5
                            : 0.0;

            px +=
                    vx * dt +
                            0.5 *
                                    ax *
                                    dt *
                                    dt;

            py +=
                    vy * dt +
                            0.5 *
                                    ay *
                                    dt *
                                    dt;

            vx += ax * dt;
            vy += ay * dt;

            imm.predict(dt);

            SimpleMatrix measurement =
                    noisyMeasurement(
                            sensor,
                            state(
                                    px,
                                    py,
                                    vx,
                                    vy
                            ),
                            random,
                            20.0,
                            0.005
                    );

            imm.update(
                    measurement,
                    sensor
            );

            double[] probability =
                    imm.getModeProbabilities();

            assertTrue(
                    probability[0] >= 0 &&
                            probability[0] <= 1
            );

            assertTrue(
                    probability[1] >= 0 &&
                            probability[1] <= 1
            );

            assertEquals(
                    1.0,
                    probability[0] +
                            probability[1],
                    1e-12
            );
        }
    }

    @Test
    void heldOutConsistencyEvaluationUsesDisjointSeeds()
            throws Exception {

        Set<Long> developmentSeeds =
                new HashSet<>();

        for (long seed = DEV_SEED_START;
             seed <= DEV_SEED_END;
             seed++) {

            developmentSeeds.add(seed);
        }

        Set<Long> validationSeeds =
                new HashSet<>();

        for (long seed = VALIDATION_SEED_START;
             seed <= VALIDATION_SEED_END;
             seed++) {

            validationSeeds.add(seed);
        }

        Set<Long> overlap =
                new HashSet<>(
                        developmentSeeds
                );

        overlap.retainAll(
                validationSeeds
        );

        assertTrue(
                overlap.isEmpty(),
                "development and validation seeds must be disjoint"
        );

        Metrics developmentEkf =
                evaluateRange(
                        DEV_SEED_START,
                        DEV_SEED_END,
                        false
                );

        Metrics developmentImm =
                evaluateRange(
                        DEV_SEED_START,
                        DEV_SEED_END,
                        true
                );

        /*
         * Parameters are fixed above before this point.
         * The validation range is never used to choose them.
         */
        Metrics validationEkf =
                evaluateRange(
                        VALIDATION_SEED_START,
                        VALIDATION_SEED_END,
                        false
                );

        Metrics validationImm =
                evaluateRange(
                        VALIDATION_SEED_START,
                        VALIDATION_SEED_END,
                        true
                );

        printMetrics(
                "development",
                "EKF",
                developmentEkf
        );

        printMetrics(
                "development",
                "IMM",
                developmentImm
        );

        printMetrics(
                "validation",
                "EKF",
                validationEkf
        );

        printMetrics(
                "validation",
                "IMM",
                validationImm
        );

        assertValidMetrics(
                validationEkf
        );

        assertValidMetrics(
                validationImm
        );
    }

    private static Metrics evaluateRange(
            long seedStart,
            long seedEnd,
            boolean useImm
    ) throws Exception {

        double positionSquaredError = 0.0;
        long positionSamples = 0;

        long neesInside = 0;
        long neesSamples = 0;

        long nisInside = 0;
        long nisSamples = 0;

        for (long seed = seedStart;
             seed <= seedEnd;
             seed++) {

            RunMetrics run =
                    evaluateOne(
                            seed,
                            useImm
                    );

            positionSquaredError +=
                    run.positionSquaredError;

            positionSamples +=
                    run.positionSamples;

            neesInside +=
                    run.neesInside;

            neesSamples +=
                    run.neesSamples;

            nisInside +=
                    run.nisInside;

            nisSamples +=
                    run.nisSamples;
        }

        return new Metrics(
                Math.sqrt(
                        positionSquaredError /
                                positionSamples
                ),
                neesInside /
                        (double) neesSamples,
                nisInside /
                        (double) nisSamples,
                positionSamples,
                neesSamples,
                nisSamples
        );
    }

    private static RunMetrics evaluateOne(
            long seed,
            boolean useImm
    ) throws Exception {

        final double sigmaRange =
                30.0;

        final double sigmaBearing =
                0.007;

        MeasurementModel sensor =
                new MeasurementModel(
                        0.0,
                        0.0,
                        sigmaRange,
                        sigmaBearing
                );

        SimpleMatrix initialState =
                state(
                        1050.0,
                        470.0,
                        20.0,
                        8.0
                );

        SimpleMatrix initialCovariance =
                SimpleMatrix
                        .identity(4)
                        .scale(2500.0);

        ExtendedKalmanFilter filter =
                useImm
                        ? new InteractingMultipleModelFilter(
                                initialState,
                                initialCovariance,
                                1.2,
                                7.5
                        )
                        : new ExtendedKalmanFilter(
                                initialState,
                                initialCovariance,
                                new MotionModel(2.0)
                        );

        Random random =
                new Random(seed);

        double px = 1000.0;
        double py = 500.0;
        double vx = 25.0;
        double vy = 5.0;

        double dt = 0.1;

        double positionSquaredError =
                0.0;

        long positionSamples =
                0;

        long neesInside =
                0;

        long neesSamples =
                0;

        long nisInside =
                0;

        long nisSamples =
                0;

        for (int step = 0;
             step < 240;
             step++) {

            double ax = 0.0;
            double ay = 0.0;

            if (step >= 60 &&
                    step < 100) {

                ax = 2.5;
                ay = -1.2;

            } else if (
                    step >= 150 &&
                            step < 185
            ) {

                ax = -2.0;
                ay = 1.6;
            }

            px +=
                    vx * dt +
                            0.5 *
                                    ax *
                                    dt *
                                    dt;

            py +=
                    vy * dt +
                            0.5 *
                                    ay *
                                    dt *
                                    dt;

            vx += ax * dt;
            vy += ay * dt;

            SimpleMatrix truth =
                    state(
                            px,
                            py,
                            vx,
                            vy
                    );

            filter.predict(dt);

            /*
             * Deterministic dropouts exercise prediction/coasting.
             */
            if (step % 19 != 0) {

                SimpleMatrix measurement =
                        noisyMeasurement(
                                sensor,
                                truth,
                                random,
                                sigmaRange,
                                sigmaBearing
                        );

                double nis =
                        filter.nis(
                                measurement,
                                sensor
                        );

                if (Double.isFinite(nis)) {

                    nisSamples++;

                    if (nis >= NIS_95_LOW &&
                            nis <= NIS_95_HIGH) {

                        nisInside++;
                    }
                }

                filter.update(
                        measurement,
                        sensor
                );
            }

            double nees =
                    filter.nees(
                            truth
                    );

            if (Double.isFinite(nees)) {

                neesSamples++;

                if (nees >= NEES_95_LOW &&
                        nees <= NEES_95_HIGH) {

                    neesInside++;
                }
            }

            double dx =
                    filter.getPx() -
                            px;

            double dy =
                    filter.getPy() -
                            py;

            positionSquaredError +=
                    dx * dx +
                            dy * dy;

            positionSamples++;
        }

        return new RunMetrics(
                positionSquaredError,
                positionSamples,
                neesInside,
                neesSamples,
                nisInside,
                nisSamples
        );
    }

    private static void printMetrics(
            String phase,
            String model,
            Metrics metrics
    ) {
        System.out.printf(
                Locale.ROOT,
                "ESTIMATION_VALIDATION phase=%s model=%s position_rmse_m=%.3f nees95_coverage=%.4f nis95_coverage=%.4f position_samples=%d nees_samples=%d nis_samples=%d%n",
                phase,
                model,
                metrics.positionRmseM,
                metrics.neesCoverage,
                metrics.nisCoverage,
                metrics.positionSamples,
                metrics.neesSamples,
                metrics.nisSamples
        );
    }

    private static void assertValidMetrics(
            Metrics metrics
    ) {
        assertTrue(
                Double.isFinite(
                        metrics.positionRmseM
                )
        );

        assertTrue(
                metrics.positionRmseM > 0
        );

        assertTrue(
                metrics.neesCoverage >= 0 &&
                        metrics.neesCoverage <= 1
        );

        assertTrue(
                metrics.nisCoverage >= 0 &&
                        metrics.nisCoverage <= 1
        );

        assertTrue(
                metrics.positionSamples > 0
        );

        assertTrue(
                metrics.neesSamples > 0
        );

        assertTrue(
                metrics.nisSamples > 0
        );
    }

    private static SimpleMatrix noisyMeasurement(
            MeasurementModel sensor,
            SimpleMatrix truth,
            Random random,
            double sigmaRange,
            double sigmaBearing
    ) {
        SimpleMatrix measurement =
                sensor.h(truth);

        measurement.set(
                0,
                0,
                measurement.get(0, 0) +
                        random.nextGaussian() *
                                sigmaRange
        );

        measurement.set(
                1,
                0,
                measurement.get(1, 0) +
                        random.nextGaussian() *
                                sigmaBearing
        );

        return measurement;
    }

    private static SimpleMatrix state(
            double px,
            double py,
            double vx,
            double vy
    ) {
        return new SimpleMatrix(
                new double[][]{
                        {px},
                        {py},
                        {vx},
                        {vy}
                }
        );
    }

    private record RunMetrics(
            double positionSquaredError,
            long positionSamples,
            long neesInside,
            long neesSamples,
            long nisInside,
            long nisSamples
    ) {}

    private record Metrics(
            double positionRmseM,
            double neesCoverage,
            double nisCoverage,
            long positionSamples,
            long neesSamples,
            long nisSamples
    ) {}
}
