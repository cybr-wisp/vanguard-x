package com.vanguard.benchmark;

import com.vanguard.tracking.association.DataAssociator;
import com.vanguard.tracking.association.MahalanobisGate;
import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import com.vanguard.tracking.estimation.MotionModel;
import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Characterizes the single-scan association ambiguity boundary.
 *
 * A fixed 4x4 target formation is observed repeatedly while inter-target
 * spacing is reduced. Sensor noise, track covariance, target count, and
 * geometry are held fixed.
 *
 * This is not an MHT/JPDA benchmark. It measures where Vanguard's current
 * gated global one-to-one assignment begins to lose association accuracy.
 */
public final class AssociationDensityBenchmark {

    private static final int SIDE = 4;
    private static final int TARGET_COUNT = SIDE * SIDE;
    private static final int TRIALS = 250;

    private static final double[] SPACING_METERS = {
            1200,
            900,
            700,
            500,
            400,
            300,
            250,
            200,
            150,
            100
    };

    private static final double SIGMA_RANGE_M = 50.0;
    private static final double SIGMA_BEARING_RAD = 0.01;

    private static final MeasurementModel SENSOR =
            new MeasurementModel(
                    0.0,
                    0.0,
                    SIGMA_RANGE_M,
                    SIGMA_BEARING_RAD
            );

    private static final MotionModel MOTION =
            new MotionModel(2.0);

    private record Truth(
            String id,
            SimpleMatrix state
    ) {}

    private record Observation(
            String truthId,
            SimpleMatrix measurement
    ) {}

    public static void main(String[] args) {

        System.out.println(
                "spacing_m," +
                "density_targets_per_km2," +
                "association_accuracy_pct," +
                "perfect_scan_pct," +
                "misassociated," +
                "unassociated"
        );

        for (double spacing : SPACING_METERS) {
            runSpacing(spacing);
        }
    }

    private static void runSpacing(double spacing) {

        List<Truth> truths =
                buildTruthFormation(spacing);

        List<DataAssociator.Candidate> candidates =
                buildCandidates(truths);

        Random random =
                new Random(
                        20260907L +
                        Math.round(spacing)
                );

        long correct = 0;
        long misassociated = 0;
        long unassociated = 0;
        long perfectScans = 0;

        for (int trial = 0;
             trial < TRIALS;
             trial++) {

            List<Observation> observations =
                    generateObservations(
                            truths,
                            random
                    );

            /*
             * Association must not receive a favorable deterministic
             * observation ordering.
             */
            Collections.shuffle(
                    observations,
                    random
            );

            List<SimpleMatrix> measurements =
                    observations.stream()
                            .map(Observation::measurement)
                            .toList();

            DataAssociator associator =
                    new DataAssociator(
                            new MahalanobisGate(9.21),
                            1_000.0
                    );

            var results =
                    associator.associateBatch(
                            measurements,
                            SENSOR,
                            candidates
                    );

            boolean perfectScan = true;

            for (int i = 0;
                 i < observations.size();
                 i++) {

                Observation observation =
                        observations.get(i);

                var result =
                        results.get(i);

                if (result instanceof
                        DataAssociator.AssociationResult.Associated hit) {

                    if (hit.trackId().equals(
                            observation.truthId())) {

                        correct++;

                    } else {

                        misassociated++;
                        perfectScan = false;
                    }

                } else {

                    unassociated++;
                    perfectScan = false;
                }
            }

            if (perfectScan) {
                perfectScans++;
            }
        }

        long totalAssociations =
                (long) TARGET_COUNT *
                TRIALS;

        double associationAccuracy =
                100.0 *
                correct /
                totalAssociations;

        double perfectScanPct =
                100.0 *
                perfectScans /
                TRIALS;

        /*
         * 4x4 formation has three inter-target intervals
         * along each dimension.
         */
        double formationSideKm =
                ((SIDE - 1) * spacing) /
                1000.0;

        double densityTargetsPerKm2 =
                TARGET_COUNT /
                (
                        formationSideKm *
                        formationSideKm
                );

        System.out.printf(
                Locale.ROOT,
                "%.0f,%.3f,%.3f,%.3f,%d,%d%n",
                spacing,
                densityTargetsPerKm2,
                associationAccuracy,
                perfectScanPct,
                misassociated,
                unassociated
        );
    }

    private static List<Truth> buildTruthFormation(
            double spacing) {

        List<Truth> truths =
                new ArrayList<>();

        double centerOffset =
                (SIDE - 1) /
                2.0;

        int id = 0;

        /*
         * Keep the formation roughly 20 km from the sensor.
         *
         * At this distance, bearing noise is materially important and
         * overlapping statistical gates emerge as spacing shrinks.
         */
        for (int row = 0;
             row < SIDE;
             row++) {

            for (int column = 0;
                 column < SIDE;
                 column++) {

                double px =
                        20_000.0 +
                        (column - centerOffset) *
                                spacing;

                double py =
                        (row - centerOffset) *
                                spacing;

                SimpleMatrix state =
                        new SimpleMatrix(
                                new double[][]{
                                        {px},
                                        {py},
                                        {0.0},
                                        {0.0}
                                }
                        );

                truths.add(
                        new Truth(
                                "T-" + id++,
                                state
                        )
                );
            }
        }

        return truths;
    }

    private static List<DataAssociator.Candidate>
    buildCandidates(
            List<Truth> truths) {

        List<DataAssociator.Candidate> candidates =
                new ArrayList<>();

        for (Truth truth : truths) {

            SimpleMatrix covariance =
                    new SimpleMatrix(
                            new double[][]{
                                    {
                                            75.0 * 75.0,
                                            0,
                                            0,
                                            0
                                    },
                                    {
                                            0,
                                            75.0 * 75.0,
                                            0,
                                            0
                                    },
                                    {
                                            0,
                                            0,
                                            100.0,
                                            0
                                    },
                                    {
                                            0,
                                            0,
                                            0,
                                            100.0
                                    }
                            }
                    );

            ExtendedKalmanFilter filter =
                    new ExtendedKalmanFilter(
                            truth.state(),
                            covariance,
                            MOTION
                    );

            candidates.add(
                    new DataAssociator.Candidate(
                            truth.id(),
                            filter
                    )
            );
        }

        return candidates;
    }

    private static List<Observation>
    generateObservations(
            List<Truth> truths,
            Random random) {

        List<Observation> observations =
                new ArrayList<>();

        for (Truth truth : truths) {

            SimpleMatrix expected =
                    SENSOR.h(
                            truth.state()
                    );

            double noisyRange =
                    expected.get(0, 0) +
                    random.nextGaussian() *
                            SIGMA_RANGE_M;

            double noisyBearing =
                    MeasurementModel.normalizeBearing(
                            expected.get(1, 0) +
                            random.nextGaussian() *
                                    SIGMA_BEARING_RAD
                    );

            SimpleMatrix measurement =
                    new SimpleMatrix(
                            new double[][]{
                                    {noisyRange},
                                    {noisyBearing}
                            }
                    );

            observations.add(
                    new Observation(
                            truth.id(),
                            measurement
                    )
            );
        }

        return observations;
    }
}
