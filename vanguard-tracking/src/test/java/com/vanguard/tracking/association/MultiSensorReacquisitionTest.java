package com.vanguard.tracking.association;

import com.vanguard.tracking.estimation.MeasurementModel;
import com.vanguard.tracking.estimation.MotionModel;
import com.vanguard.tracking.lifecycle.Track;
import com.vanguard.tracking.lifecycle.TrackManager;
import com.vanguard.tracking.lifecycle.TrackState;

import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MultiSensorReacquisitionTest {

    private static final MotionModel MOTION =
            new MotionModel(1.0);

    private record Sensor(
            String id,
            double x,
            double y,
            MeasurementModel model,
            Random rng
    ) {}

    @Test
    void threeSensorDropoutPreservesCanonicalTrackIdentity() {

        /*
         * DIAGNOSTIC:
         *
         * Keep the statistical Mahalanobis gate unchanged at the default
         * chi-square threshold of 9.21.
         *
         * Only enlarge the coarse spatial candidate-pruning grid from
         * DataAssociator's production default candidate grid.
         *
         * If fragmentation disappears, the spatial pre-filter was rejecting
         * statistically valid cross-sensor candidates before Mahalanobis
         * gating could evaluate them.
         */
        DataAssociator associator =
                new DataAssociator();

        TrackManager manager =
                new TrackManager(
                        associator,
                        MOTION,
                        3,
                        3,
                        45,
                        10_000,
                        62_500
                );

        double[][] targets = {
                {-15_000,  2_000,  205,    8},
                { 14_000,  6_000, -195,  -18},
                { -7_000, -8_500,   55,  190},
                {-12_000,  8_000,  175,  -70},
                { 11_500, -7_500, -165,  135},
                { -2_500, -9_000,   25,  185},
                { 13_500,  1_000, -190,   20},
                {-16_000, -5_000,  200,   75},
                {  6_000,  9_500, -110, -175},
                { -4_000, 10_000,   95, -170}
        };

        List<Sensor> sensors =
                List.of(
                        sensor(
                                "SSA-01",
                                -18_400,
                                5_550,
                                101
                        ),
                        sensor(
                                "SSB-02",
                                -21_160,
                                -3_330,
                                202
                        ),
                        sensor(
                                "SSC-03",
                                9_200,
                                -6_660,
                                303
                        )
                );

        final int dropoutTargetIndex = 6;
        final long cadenceMs = 70L;
        final long startMs = 1_000L;

        long timestampMs = startMs;

        /*
         * Establish tracks using the important runtime property:
         * every sensor batch is processed separately at the SAME timestamp.
         */
        for (int step = 0; step < 50; step++) {

            processTimestamp(
                    manager,
                    sensors,
                    targets,
                    timestampMs,
                    startMs,
                    -1
            );

            timestampMs += cadenceMs;
        }

        /*
         * The tracker should represent ten physical targets with ten
         * canonical live tracks before the dropout even begins.
         */
        assertEquals(
                10,
                manager.getAliveCount(),
                "Multi-sensor establishment produced fragmented live tracks"
        );

        assertEquals(
                10,
                manager.getConfirmedCount(),
                "All ten established targets should be confirmed"
        );

        double[] target7 =
                targets[dropoutTargetIndex];

        double elapsed =
                ((timestampMs - cadenceMs) - startMs)
                        / 1000.0;

        double truthX =
                target7[0]
                        + target7[2] * elapsed;

        double truthY =
                target7[1]
                        + target7[3] * elapsed;

        Track canonical =
                manager.getAliveTracks()
                        .stream()
                        .min(
                                java.util.Comparator.comparingDouble(
                                        track ->
                                                Math.hypot(
                                                        track.getPx()
                                                                - truthX,
                                                        track.getPy()
                                                                - truthY
                                                )
                                )
                        )
                        .orElseThrow();

        String canonicalId =
                canonical.getTrackId();

        assertEquals(
                TrackState.CONFIRMED,
                canonical.getState()
        );

        int createdBeforeDropout =
                manager.getTotalCreated();

        /*
         * Approximately two seconds with TGT-07 absent from ALL sensors.
         */
        for (int step = 0; step < 29; step++) {

            processTimestamp(
                    manager,
                    sensors,
                    targets,
                    timestampMs,
                    startMs,
                    dropoutTargetIndex
            );

            timestampMs += cadenceMs;
        }

        /*
         * One additional timestamp advances/finalizes the previous
         * observation cycle while TGT-07 remains absent.
         */
        processTimestamp(
                manager,
                sensors,
                targets,
                timestampMs,
                startMs,
                dropoutTargetIndex
        );

        timestampMs += cadenceMs;

        Track coasting =
                manager.getTrack(canonicalId)
                        .orElseThrow();

        assertEquals(
                TrackState.COASTING,
                coasting.getState(),
                "Canonical TGT-07 track should coast during blackout"
        );

        int createdDuringDropout =
                manager.getTotalCreated();

        /*
         * Restore TGT-07 to every sensor.
         */
        processTimestamp(
                manager,
                sensors,
                targets,
                timestampMs,
                startMs,
                -1
        );

        Track reacquired =
                manager.getTrack(canonicalId)
                        .orElseThrow();

        assertEquals(
                TrackState.CONFIRMED,
                reacquired.getState(),
                "TGT-07 should reacquire into its canonical track"
        );

        double recoveryElapsed =
                (timestampMs - startMs)
                        / 1000.0;

        double recoveryTruthX =
                target7[0]
                        + target7[2] * recoveryElapsed;

        double recoveryTruthY =
                target7[1]
                        + target7[3] * recoveryElapsed;

        double positionError =
                Math.hypot(
                        reacquired.getPx()
                                - recoveryTruthX,
                        reacquired.getPy()
                                - recoveryTruthY
                );

        int totalCreated =
                manager.getTotalCreated();

        System.out.printf(
                "%nTHREE-SENSOR REACQUISITION METRICS%n" +
                        "canonicalTrackId=%s%n" +
                        "configuredBlackoutMs=%d%n" +
                        "createdBeforeDropout=%d%n" +
                        "createdDuringDropout=%d%n" +
                        "totalCreated=%d%n" +
                        "aliveTracks=%d%n" +
                        "confirmedTracks=%d%n" +
                        "positionErrorM=%.3f%n",
                canonicalId,
                29 * cadenceMs,
                createdBeforeDropout,
                createdDuringDropout,
                totalCreated,
                manager.getAliveCount(),
                manager.getConfirmedCount(),
                positionError
        );

        /*
         * These assertions intentionally expose fragmentation rather
         * than hiding it.
         */
        assertEquals(
                10,
                manager.getAliveCount(),
                "Three-sensor reacquisition created extra live hypotheses"
        );

        assertEquals(
                10,
                manager.getConfirmedCount(),
                "All ten targets should remain confirmed after reacquisition"
        );

        assertEquals(
                10,
                totalCreated,
                "Three-sensor processing created replacement/duplicate tracks"
        );
    }

    private static Sensor sensor(
            String id,
            double x,
            double y,
            long seed) {

        return new Sensor(
                id,
                x,
                y,
                new MeasurementModel(
                        x,
                        y,
                        50,
                        0.01
                ),
                new Random(seed)
        );
    }

    private static void processTimestamp(
            TrackManager manager,
            List<Sensor> sensors,
            double[][] targets,
            long timestampMs,
            long startMs,
            int suppressedTarget) {

        double elapsed =
                (timestampMs - startMs)
                        / 1000.0;

        for (Sensor sensor : sensors) {

            ArrayList<SimpleMatrix> measurements =
                    new ArrayList<>();

            for (int i = 0; i < targets.length; i++) {

                if (i == suppressedTarget) {
                    continue;
                }

                double[] target =
                        targets[i];

                double px =
                        target[0]
                                + target[2] * elapsed;

                double py =
                        target[1]
                                + target[3] * elapsed;

                double dx =
                        px - sensor.x();

                double dy =
                        py - sensor.y();

                double range =
                        Math.hypot(dx, dy)
                                + 5.0
                                + sensor.rng()
                                        .nextGaussian()
                                        * 50.0;

                double bearing =
                        Math.atan2(dy, dx)
                                + 0.001
                                + sensor.rng()
                                        .nextGaussian()
                                        * 0.01;

                measurements.add(
                        new SimpleMatrix(
                                new double[][]{
                                        {range},
                                        {bearing}
                                }
                        )
                );
            }

            manager.processObservations(
                    measurements,
                    sensor.model(),
                    sensor.id(),
                    timestampMs
            );
        }

        manager.suppressDuplicateTracks();
    }
}