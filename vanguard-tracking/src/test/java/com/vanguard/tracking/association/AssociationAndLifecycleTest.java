package com.vanguard.tracking.association;

import com.vanguard.tracking.estimation.*;
import com.vanguard.tracking.lifecycle.*;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssociationAndLifecycleTest {

    static final MotionModel MOTION = new MotionModel(1.0);
    static final MeasurementModel SENSOR = new MeasurementModel(0, 0, 50, 0.01);

    static ExtendedKalmanFilter makeEkf(double px, double py, double vx, double vy) {
        SimpleMatrix x = new SimpleMatrix(new double[][]{{px}, {py}, {vx}, {vy}});
        SimpleMatrix P = SimpleMatrix.identity(4).scale(1000);
        return new ExtendedKalmanFilter(x, P, MOTION);
    }

    // ================================================================
    // Day 8: MahalanobisGate
    // ================================================================

    @Nested
    @DisplayName("MahalanobisGate")
    class GateTests {

        @Test
        @DisplayName("Matching observation passes the gate")
        void matchingPasses() {
            MahalanobisGate gate = new MahalanobisGate();
            ExtendedKalmanFilter ekf = makeEkf(500, 500, 0, 0);
            SimpleMatrix z = SENSOR.h(ekf.getState()); // perfect measurement
            SimpleMatrix[] innov = ekf.computeInnovation(z, SENSOR);
            double d2 = gate.squaredDistance(innov[0], innov[1]);
            assertTrue(gate.isInsideGate(d2), "Perfect measurement should pass gate, d2=" + d2);
            assertTrue(d2 < 1e-6, "d2 should be near zero for perfect match");
        }

        @Test
        @DisplayName("Outlier is rejected by the gate")
        void outlierRejected() {
            MahalanobisGate gate = new MahalanobisGate(9.21);
            ExtendedKalmanFilter ekf = makeEkf(500, 500, 0, 0);
            // Observation far away from predicted
            SimpleMatrix z = new SimpleMatrix(new double[][]{{5000}, {2.5}});
            SimpleMatrix[] innov = ekf.computeInnovation(z, SENSOR);
            double d2 = gate.squaredDistance(innov[0], innov[1]);
            assertFalse(gate.isInsideGate(d2), "Outlier should be rejected, d2=" + d2);
        }

        @Test
        @DisplayName("Noisy but plausible observation passes")
        void noisyPlausible() {
            MahalanobisGate gate = new MahalanobisGate();
            ExtendedKalmanFilter ekf = makeEkf(500, 500, 0, 0);
            SimpleMatrix hx = SENSOR.h(ekf.getState());
            // Add moderate noise within the expected sigma band
            SimpleMatrix z = new SimpleMatrix(new double[][]{
                    {hx.get(0) + 30}, {hx.get(1) + 0.005}});
            SimpleMatrix[] innov = ekf.computeInnovation(z, SENSOR);
            double d2 = gate.squaredDistance(innov[0], innov[1]);
            assertTrue(gate.isInsideGate(d2), "Noisy but plausible should pass, d2=" + d2);
        }
    }

    // ================================================================
    // Day 8: DataAssociator
    // ================================================================

    @Nested
    @DisplayName("DataAssociator")
    class AssociatorTests {

        @Test
        @DisplayName("Single track, matching observation -> associated")
        void singleTrackMatch() {
            DataAssociator assoc = new DataAssociator();
            ExtendedKalmanFilter ekf = makeEkf(1000, 500, 10, 5);
            SimpleMatrix z = SENSOR.h(ekf.getState());

            var result = assoc.associate(z, SENSOR,
                    List.of(new DataAssociator.Candidate("T1", ekf)));
            assertInstanceOf(DataAssociator.AssociationResult.Associated.class, result);
            assertEquals("T1", ((DataAssociator.AssociationResult.Associated) result).trackId());
        }

        @Test
        @DisplayName("No tracks -> unassociated")
        void noTracks() {
            DataAssociator assoc = new DataAssociator();
            SimpleMatrix z = new SimpleMatrix(new double[][]{{1000}, {0.5}});
            var result = assoc.associate(z, SENSOR, List.of());
            assertInstanceOf(DataAssociator.AssociationResult.Unassociated.class, result);
        }

        @Test
        @DisplayName("Two close tracks: nearest wins")
        void twoCloseTracksNearestWins() {
            DataAssociator assoc = new DataAssociator();
            ExtendedKalmanFilter ekf1 = makeEkf(1000, 500, 0, 0);
            ExtendedKalmanFilter ekf2 = makeEkf(1050, 520, 0, 0);
            // Observation closer to track 1
            SimpleMatrix z = SENSOR.h(ekf1.getState());

            var result = assoc.associate(z, SENSOR, List.of(
                    new DataAssociator.Candidate("T1", ekf1),
                    new DataAssociator.Candidate("T2", ekf2)));

            assertInstanceOf(DataAssociator.AssociationResult.Associated.class, result);
            assertEquals("T1", ((DataAssociator.AssociationResult.Associated) result).trackId());
        }

        @Test
        @DisplayName("Ambiguous two-target case: each observation gets different track")
        void ambiguousTwoTarget() {
            DataAssociator assoc = new DataAssociator();
            ExtendedKalmanFilter ekf1 = makeEkf(1000, 500, 0, 0);
            ExtendedKalmanFilter ekf2 = makeEkf(1100, 600, 0, 0);

            SimpleMatrix z1 = SENSOR.h(ekf1.getState());
            SimpleMatrix z2 = SENSOR.h(ekf2.getState());

            var results = assoc.associateBatch(List.of(z1, z2), SENSOR, List.of(
                    new DataAssociator.Candidate("T1", ekf1),
                    new DataAssociator.Candidate("T2", ekf2)));

            // Both should associate, to different tracks
            var r0 = results.get(0);
            var r1 = results.get(1);
            assertInstanceOf(DataAssociator.AssociationResult.Associated.class, r0);
            assertInstanceOf(DataAssociator.AssociationResult.Associated.class, r1);
            assertNotEquals(
                    ((DataAssociator.AssociationResult.Associated) r0).trackId(),
                    ((DataAssociator.AssociationResult.Associated) r1).trackId(),
                    "Each observation should claim a different track");
        }
    }

    // ================================================================
    // Tracking regression tests
    // ================================================================

    @Nested
    @DisplayName("Tracking regressions")
    class TrackingRegressionTests {

        @Test
        @DisplayName("Claimed nearest candidate falls back to second valid track")
        void claimedNearestFallsBackToSecondCandidate() {
            DataAssociator assoc = new DataAssociator();

            ExtendedKalmanFilter ekf1 =
                    makeEkf(1000, 500, 0, 0);

            ExtendedKalmanFilter ekf2 =
                    makeEkf(1040, 500, 0, 0);

            SimpleMatrix z1 =
                    SENSOR.h(ekf1.getState());

            ExtendedKalmanFilter observation2 =
                    makeEkf(1010, 500, 0, 0);

            SimpleMatrix z2 =
                    SENSOR.h(observation2.getState());

            Map<Integer, DataAssociator.AssociationResult> results =
                    assoc.associateBatch(
                            List.of(z1, z2),
                            SENSOR,
                            List.of(
                                    new DataAssociator.Candidate("T1", ekf1),
                                    new DataAssociator.Candidate("T2", ekf2)
                            )
                    );

            assertInstanceOf(
                    DataAssociator.AssociationResult.Associated.class,
                    results.get(0)
            );

            assertInstanceOf(
                    DataAssociator.AssociationResult.Associated.class,
                    results.get(1)
            );

            assertEquals(
                    "T1",
                    ((DataAssociator.AssociationResult.Associated)
                            results.get(0)).trackId()
            );

            assertEquals(
                    "T2",
                    ((DataAssociator.AssociationResult.Associated)
                            results.get(1)).trackId()
            );
        }

        @Test
        @DisplayName("Global assignment prevents greedy starvation")
        void globalAssignmentPreventsGreedyStarvation() {
            DataAssociator assoc =
                    new DataAssociator(
                            new MahalanobisGate(9.21),
                            1_000.0
                    );

            ExtendedKalmanFilter track1 =
                    makeEkf(
                            1000,
                            0,
                            0,
                            0
                    );

            ExtendedKalmanFilter track2 =
                    makeEkf(
                            1200,
                            0,
                            0,
                            0
                    );

            /*
             * Flexible observation:
             * prefers T1, but can legitimately match T2.
             */
            SimpleMatrix flexibleObservation =
                    SENSOR.h(
                            makeEkf(
                                    1080,
                                    0,
                                    0,
                                    0
                            ).getState()
                    );

            /*
             * Constrained observation:
             * can match T1, while T2 is outside the Mahalanobis gate.
             *
             * A greedy input-order algorithm assigns the flexible
             * observation to T1 first and leaves this observation unmatched.
             * The global optimum is:
             *
             * flexible    -> T2
             * constrained -> T1
             */
            SimpleMatrix constrainedObservation =
                    SENSOR.h(
                            makeEkf(
                                    990,
                                    0,
                                    0,
                                    0
                            ).getState()
                    );

            Map<Integer, DataAssociator.AssociationResult> results =
                    assoc.associateBatch(
                            List.of(
                                    flexibleObservation,
                                    constrainedObservation
                            ),
                            SENSOR,
                            List.of(
                                    new DataAssociator.Candidate(
                                            "T1",
                                            track1
                                    ),
                                    new DataAssociator.Candidate(
                                            "T2",
                                            track2
                                    )
                            )
                    );

            assertInstanceOf(
                    DataAssociator.AssociationResult.Associated.class,
                    results.get(0)
            );

            assertInstanceOf(
                    DataAssociator.AssociationResult.Associated.class,
                    results.get(1)
            );

            assertEquals(
                    "T2",
                    ((DataAssociator.AssociationResult.Associated)
                            results.get(0)).trackId(),
                    "Flexible observation should yield T1 to constrained observation"
            );

            assertEquals(
                    "T1",
                    ((DataAssociator.AssociationResult.Associated)
                            results.get(1)).trackId(),
                    "Constrained observation must retain its only valid track"
            );
        }

        @Test
        @DisplayName("Two-second dropout preserves canonical track ID on reacquisition")
        void twoSecondDropoutPreservesTrackIdentity() {
            TrackManager manager =
                    new TrackManager(
                            new DataAssociator(),
                            MOTION,
                            3,
                            3,
                            45,
                            10_000,
                            62_500
                    );

            double initialX = 13_500.0;
            double initialY = 1_000.0;
            double vx = -190.0;
            double vy = 20.0;

            long timestampMs = 1_000L;
            long cadenceMs = 70L;

            // Establish one confirmed canonical track.
            for (int i = 0; i < 50; i++) {
                double elapsedSeconds =
                        (timestampMs - 1_000L) / 1000.0;

                SimpleMatrix truthState =
                        new SimpleMatrix(
                                new double[][]{
                                        {initialX + vx * elapsedSeconds},
                                        {initialY + vy * elapsedSeconds},
                                        {vx},
                                        {vy}
                                }
                        );

                SimpleMatrix measurement =
                        SENSOR.h(truthState);

                manager.processObservations(
                        List.of(measurement),
                        SENSOR,
                        "S1",
                        timestampMs
                );

                timestampMs += cadenceMs;
            }

            assertEquals(
                    1,
                    manager.getAliveCount(),
                    "Exactly one canonical track should exist before dropout"
            );

            Track originalTrack =
                    manager.getAliveTracks()
                            .iterator()
                            .next();

            String originalTrackId =
                    originalTrack.getTrackId();

            assertEquals(
                    TrackState.CONFIRMED,
                    originalTrack.getState(),
                    "Track must be confirmed before dropout"
            );

            double uncertaintyBeforeDropout =
                    originalTrack.getPositionUncertainty();

            // 29 x 70 ms = 2030 ms of missing observations.
            for (int i = 0; i < 29; i++) {
                manager.processObservations(
                        List.of(),
                        SENSOR,
                        "S1",
                        timestampMs
                );

                timestampMs += cadenceMs;
            }

            // Advance once to finalize the previous empty lifecycle cycle.
            manager.processObservations(
                    List.of(),
                    SENSOR,
                    "S1",
                    timestampMs
            );

            timestampMs += cadenceMs;

            Track coastingTrack =
                    manager.getTrack(originalTrackId)
                            .orElseThrow();

            assertEquals(
                    TrackState.COASTING,
                    coastingTrack.getState(),
                    "Canonical track should coast during the blackout"
            );

            assertTrue(
                    coastingTrack.getPositionUncertainty()
                            > uncertaintyBeforeDropout,
                    "Position uncertainty should grow while coasting"
            );

            assertEquals(
                    1,
                    manager.getAliveCount(),
                    "Blackout must not create another live track"
            );

            // Truth continues moving while observations are absent.
            double elapsedSeconds =
                    (timestampMs - 1_000L) / 1000.0;

            SimpleMatrix resumedTruthState =
                    new SimpleMatrix(
                            new double[][]{
                                    {initialX + vx * elapsedSeconds},
                                    {initialY + vy * elapsedSeconds},
                                    {vx},
                                    {vy}
                            }
                    );

            SimpleMatrix resumedMeasurement =
                    SENSOR.h(resumedTruthState);

            long recoveryStartNs =
                    System.nanoTime();

            manager.processObservations(
                    List.of(resumedMeasurement),
                    SENSOR,
                    "S1",
                    timestampMs
            );

            long recoveryProcessingNs =
                    System.nanoTime() - recoveryStartNs;

            Track reacquiredTrack =
                    manager.getTrack(originalTrackId)
                            .orElseThrow();

            assertEquals(
                    TrackState.CONFIRMED,
                    reacquiredTrack.getState(),
                    "Same canonical track must return to CONFIRMED"
            );

            assertEquals(
                    originalTrackId,
                    reacquiredTrack.getTrackId(),
                    "Track ID must remain unchanged"
            );

            assertEquals(
                    1,
                    manager.getAliveCount(),
                    "Reacquisition must not leave duplicate live tracks"
            );

            assertEquals(
                    1,
                    manager.getTotalCreated(),
                    "Reacquisition must not create a replacement track"
            );

            double positionErrorMeters =
                    Math.hypot(
                            reacquiredTrack.getPx()
                                    - resumedTruthState.get(0),
                            reacquiredTrack.getPy()
                                    - resumedTruthState.get(1)
                    );

            System.out.printf(
                    "%nREACQUISITION METRICS%n" +
                            "trackId=%s%n" +
                            "blackoutMs=%d%n" +
                            "recoveryProcessingMs=%.3f%n" +
                            "positionErrorM=%.3f%n" +
                            "uncertaintyBefore=%.3f%n" +
                            "uncertaintyAfter=%.3f%n" +
                            "aliveTracks=%d%n" +
                            "totalCreated=%d%n",
                    originalTrackId,
                    29 * cadenceMs,
                    recoveryProcessingNs / 1_000_000.0,
                    positionErrorMeters,
                    uncertaintyBeforeDropout,
                    reacquiredTrack.getPositionUncertainty(),
                    manager.getAliveCount(),
                    manager.getTotalCreated()
            );
        }

        @Test
        @DisplayName("Ten-target scenario preserves canonical track through two-second dropout")
        void multiTargetDropoutPreservesCanonicalTrackIdentity() {
            TrackManager manager =
                    new TrackManager(
                            new DataAssociator(),
                            MOTION,
                            3,
                            3,
                            45,
                            10_000,
                            62_500
                    );

            double[][] targets = {
                    {-15_000.0,  2_000.0,  205.0,    8.0},
                    { 14_000.0,  6_000.0, -195.0,  -18.0},
                    { -7_000.0, -8_500.0,   55.0,  190.0},
                    {-12_000.0,  8_000.0,  175.0,  -70.0},
                    { 11_500.0, -7_500.0, -165.0,  135.0},
                    { -2_500.0, -9_000.0,   25.0,  185.0},
                    { 13_500.0,  1_000.0, -190.0,   20.0},
                    {-16_000.0, -5_000.0,  200.0,   75.0},
                    {  6_000.0,  9_500.0, -110.0, -175.0},
                    { -4_000.0, 10_000.0,   95.0, -170.0}
            };

            final int dropoutTargetIndex = 6;
            final long startTimestampMs = 1_000L;
            final long cadenceMs = 70L;

            long timestampMs = startTimestampMs;

            // Establish ten canonical tracks.
            for (int step = 0; step < 50; step++) {
                double elapsedSeconds =
                        (timestampMs - startTimestampMs) / 1000.0;

                java.util.ArrayList<SimpleMatrix> observations =
                        new java.util.ArrayList<>();

                for (double[] target : targets) {
                    SimpleMatrix truthState =
                            new SimpleMatrix(
                                    new double[][]{
                                            {
                                                    target[0]
                                                            + target[2]
                                                            * elapsedSeconds
                                            },
                                            {
                                                    target[1]
                                                            + target[3]
                                                            * elapsedSeconds
                                            },
                                            {target[2]},
                                            {target[3]}
                                    }
                            );

                    observations.add(SENSOR.h(truthState));
                }

                manager.processObservations(
                        observations,
                        SENSOR,
                        "S1",
                        timestampMs
                );

                timestampMs += cadenceMs;
            }

            assertEquals(
                    10,
                    manager.getAliveCount(),
                    "Exactly ten live tracks should exist before dropout"
            );

            assertEquals(
                    10,
                    manager.getConfirmedCount(),
                    "All ten tracks should be confirmed before dropout"
            );

            assertEquals(
                    10,
                    manager.getTotalCreated(),
                    "Establishment must not create duplicate tracks"
            );

            double[] dropoutTarget =
                    targets[dropoutTargetIndex];

            double preDropoutElapsedSeconds =
                    ((timestampMs - cadenceMs) - startTimestampMs)
                            / 1000.0;

            double dropoutTruthX =
                    dropoutTarget[0]
                            + dropoutTarget[2]
                            * preDropoutElapsedSeconds;

            double dropoutTruthY =
                    dropoutTarget[1]
                            + dropoutTarget[3]
                            * preDropoutElapsedSeconds;

            Track canonicalTrack = null;
            double canonicalDistance =
                    Double.POSITIVE_INFINITY;

            for (Track track : manager.getAliveTracks()) {
                double distance =
                        Math.hypot(
                                track.getPx() - dropoutTruthX,
                                track.getPy() - dropoutTruthY
                        );

                if (distance < canonicalDistance) {
                    canonicalDistance = distance;
                    canonicalTrack = track;
                }
            }

            assertNotNull(
                    canonicalTrack,
                    "TGT-07 canonical track must exist"
            );

            String canonicalTrackId =
                    canonicalTrack.getTrackId();

            assertEquals(
                    TrackState.CONFIRMED,
                    canonicalTrack.getState(),
                    "TGT-07 must be confirmed before dropout"
            );

            // Suppress TGT-07 while the other nine continue updating.
            for (int blackoutStep = 0;
                 blackoutStep < 29;
                 blackoutStep++) {

                double elapsedSeconds =
                        (timestampMs - startTimestampMs) / 1000.0;

                java.util.ArrayList<SimpleMatrix> observations =
                        new java.util.ArrayList<>();

                for (int targetIndex = 0;
                     targetIndex < targets.length;
                     targetIndex++) {

                    if (targetIndex == dropoutTargetIndex) {
                        continue;
                    }

                    double[] target =
                            targets[targetIndex];

                    SimpleMatrix truthState =
                            new SimpleMatrix(
                                    new double[][]{
                                            {
                                                    target[0]
                                                            + target[2]
                                                            * elapsedSeconds
                                            },
                                            {
                                                    target[1]
                                                            + target[3]
                                                            * elapsedSeconds
                                            },
                                            {target[2]},
                                            {target[3]}
                                    }
                            );

                    observations.add(
                            SENSOR.h(truthState)
                    );
                }

                manager.processObservations(
                        observations,
                        SENSOR,
                        "S1",
                        timestampMs
                );

                timestampMs += cadenceMs;
            }

            // Finalize the previous blackout cycle deterministically.
            {
                double elapsedSeconds =
                        (timestampMs - startTimestampMs) / 1000.0;

                java.util.ArrayList<SimpleMatrix> observations =
                        new java.util.ArrayList<>();

                for (int targetIndex = 0;
                     targetIndex < targets.length;
                     targetIndex++) {

                    if (targetIndex == dropoutTargetIndex) {
                        continue;
                    }

                    double[] target =
                            targets[targetIndex];

                    SimpleMatrix truthState =
                            new SimpleMatrix(
                                    new double[][]{
                                            {
                                                    target[0]
                                                            + target[2]
                                                            * elapsedSeconds
                                            },
                                            {
                                                    target[1]
                                                            + target[3]
                                                            * elapsedSeconds
                                            },
                                            {target[2]},
                                            {target[3]}
                                    }
                            );

                    observations.add(
                            SENSOR.h(truthState)
                    );
                }

                manager.processObservations(
                        observations,
                        SENSOR,
                        "S1",
                        timestampMs
                );
            }

            timestampMs += cadenceMs;

            Track coastingTrack =
                    manager.getTrack(canonicalTrackId)
                            .orElseThrow();

            assertEquals(
                    TrackState.COASTING,
                    coastingTrack.getState(),
                    "TGT-07 canonical track should coast during dropout"
            );

            assertEquals(
                    10,
                    manager.getAliveCount(),
                    "Dropout must preserve ten live tracks"
            );

            assertEquals(
                    10,
                    manager.getTotalCreated(),
                    "Dropout must not create replacement tracks"
            );

            double recoveryElapsedSeconds =
                    (timestampMs - startTimestampMs) / 1000.0;

            java.util.ArrayList<SimpleMatrix> recoveryObservations =
                    new java.util.ArrayList<>();

            SimpleMatrix dropoutTargetRecoveryTruth =
                    null;

            for (int targetIndex = 0;
                 targetIndex < targets.length;
                 targetIndex++) {

                double[] target =
                        targets[targetIndex];

                SimpleMatrix truthState =
                        new SimpleMatrix(
                                new double[][]{
                                        {
                                                target[0]
                                                        + target[2]
                                                        * recoveryElapsedSeconds
                                        },
                                        {
                                                target[1]
                                                        + target[3]
                                                        * recoveryElapsedSeconds
                                        },
                                        {target[2]},
                                        {target[3]}
                                }
                        );

                recoveryObservations.add(
                        SENSOR.h(truthState)
                );

                if (targetIndex == dropoutTargetIndex) {
                    dropoutTargetRecoveryTruth =
                            truthState;
                }
            }

            assertNotNull(dropoutTargetRecoveryTruth);

            long recoveryStartNs =
                    System.nanoTime();

            manager.processObservations(
                    recoveryObservations,
                    SENSOR,
                    "S1",
                    timestampMs
            );

            long recoveryProcessingNs =
                    System.nanoTime() - recoveryStartNs;

            Track reacquiredTrack =
                    manager.getTrack(canonicalTrackId)
                            .orElseThrow();

            assertEquals(
                    TrackState.CONFIRMED,
                    reacquiredTrack.getState(),
                    "TGT-07 must reacquire into its original canonical track"
            );

            assertEquals(
                    canonicalTrackId,
                    reacquiredTrack.getTrackId(),
                    "TGT-07 track ID must remain unchanged"
            );

            assertEquals(
                    10,
                    manager.getAliveCount(),
                    "Reacquisition must leave exactly ten live tracks"
            );

            assertEquals(
                    10,
                    manager.getConfirmedCount(),
                    "All ten tracks should be confirmed after reacquisition"
            );

            assertEquals(
                    10,
                    manager.getTotalCreated(),
                    "Reacquisition must not create a replacement track"
            );

            double positionErrorMeters =
                    Math.hypot(
                            reacquiredTrack.getPx()
                                    - dropoutTargetRecoveryTruth.get(0),
                            reacquiredTrack.getPy()
                                    - dropoutTargetRecoveryTruth.get(1)
                    );

            System.out.printf(
                    "%nMULTI-TARGET REACQUISITION METRICS%n" +
                            "canonicalTrackId=%s%n" +
                            "blackoutMs=%d%n" +
                            "recoveryProcessingMs=%.3f%n" +
                            "positionErrorM=%.3f%n" +
                            "aliveTracks=%d%n" +
                            "confirmedTracks=%d%n" +
                            "totalCreated=%d%n",
                    canonicalTrackId,
                    29 * cadenceMs,
                    recoveryProcessingNs / 1_000_000.0,
                    positionErrorMeters,
                    manager.getAliveCount(),
                    manager.getConfirmedCount(),
                    manager.getTotalCreated()
            );
        }
        @Test
        @DisplayName("New track does not immediately receive a miss")
        void newTrackDoesNotImmediatelyMiss() {
            TrackManager manager =
                    new TrackManager(
                            new DataAssociator(),
                            MOTION,
                            3,
                            3,
                            8,
                            10_000,
                            62_500
                    );

            ExtendedKalmanFilter truth =
                    makeEkf(1000, 500, 0, 0);

            SimpleMatrix z =
                    SENSOR.h(truth.getState());

            manager.processObservations(
                    List.of(z),
                    SENSOR,
                    "S1",
                    1000
            );

            manager.processObservations(List.of(), SENSOR, "S1", 1001);

            Track track =
                    manager.getAllTracks()
                            .iterator()
                            .next();

            assertEquals(
                    0,
                    track.getConsecutiveMisses()
            );

            assertEquals(
                    1,
                    track.getConsecutiveHits()
            );
        }

        @Test
        @DisplayName("Multiple sensors at one timestamp count as one miss cycle")
        void multipleSensorsSameTimestampCountAsOneMiss() {
            TrackManager manager =
                    new TrackManager(
                            new DataAssociator(),
                            MOTION,
                            3,
                            3,
                            8,
                            10_000,
                            62_500
                    );

            ExtendedKalmanFilter truth =
                    makeEkf(1000, 500, 0, 0);

            SimpleMatrix z =
                    SENSOR.h(truth.getState());

            manager.processObservations(
                    List.of(z),
                    SENSOR,
                    "S1",
                    1000
            );

            manager.processObservations(
                    List.of(z),
                    SENSOR,
                    "S2",
                    1000
            );

            // Advancing to 2000 finalizes the corroborated 1000 birth.
            manager.processObservations(
                    List.of(),
                    SENSOR,
                    "S1",
                    2000
            );

            Track track =
                    manager.getAllTracks()
                            .iterator()
                            .next();

            assertEquals(
                    0,
                    track.getConsecutiveMisses()
            );

            manager.processObservations(
                    List.of(),
                    SENSOR,
                    "S2",
                    2000
            );

            manager.processObservations(
                    List.of(),
                    SENSOR,
                    "S3",
                    2000
            );

            // Advancing to 3000 finalizes timestamp 2000 exactly once.
            manager.processObservations(
                    List.of(),
                    SENSOR,
                    "S1",
                    3000
            );

            assertEquals(
                    1,
                    track.getConsecutiveMisses()
            );
        }    }
    // ================================================================
    // Day 9: Track lifecycle
    // ================================================================

    @Nested
    @DisplayName("Track lifecycle")
    class LifecycleTests {

        Track makeTrack() {
            return new Track("T1", makeEkf(500, 500, 10, 5), MOTION, 0,
                    3, 3, 8);
        }

        @Test
        @DisplayName("New track starts TENTATIVE")
        void startsTentative() {
            assertEquals(TrackState.TENTATIVE, makeTrack().getState());
        }

        @Test
        @DisplayName("Track confirms after hitsToConfirm observations")
        void confirmsAfterHits() {
            Track t = makeTrack(); // starts with 1 hit from construction
            SimpleMatrix z = SENSOR.h(t.getEkf().getState());
            t.update(z, SENSOR, "S1", 1000);
            assertEquals(TrackState.TENTATIVE, t.getState(), "2 hits, not yet confirmed");
            t.update(z, SENSOR, "S2", 2000);
            assertEquals(TrackState.CONFIRMED, t.getState(), "3 hits -> confirmed");
        }

        @Test
        @DisplayName("Confirmed track starts coasting after missesToCoast misses")
        void coastingAfterMisses() {
            Track t = makeTrack();
            SimpleMatrix z = SENSOR.h(t.getEkf().getState());
            t.update(z, SENSOR, "S1", 1000);
            t.update(z, SENSOR, "S1", 2000); // now confirmed

            t.recordMiss(3000);
            t.recordMiss(4000);
            assertEquals(TrackState.CONFIRMED, t.getState(), "2 misses, still confirmed");
            t.recordMiss(5000);
            assertEquals(TrackState.COASTING, t.getState(), "3 misses -> coasting");
        }

        @Test
        @DisplayName("Coasting track drops after missesToDrop total misses")
        void dropsAfterTooManyMisses() {
            Track t = makeTrack();
            SimpleMatrix z = SENSOR.h(t.getEkf().getState());
            t.update(z, SENSOR, "S1", 1000);
            t.update(z, SENSOR, "S1", 2000); // confirmed

            for (int i = 0; i < 8; i++) {
                t.recordMiss(3000 + i * 1000);
            }
            assertEquals(TrackState.DROPPED, t.getState());
        }

        @Test
        @DisplayName("Coasting track reacquires on new observation (no duplicate)")
        void reacquisition() {
            Track t = makeTrack();
            SimpleMatrix z = SENSOR.h(t.getEkf().getState());
            t.update(z, SENSOR, "S1", 1000);
            t.update(z, SENSOR, "S1", 2000); // confirmed

            // Enter coasting
            t.recordMiss(3000);
            t.recordMiss(4000);
            t.recordMiss(5000);
            assertEquals(TrackState.COASTING, t.getState());

            // Reacquire
            t.update(z, SENSOR, "S1", 6000);
            assertEquals(TrackState.CONFIRMED, t.getState(), "Should reacquire to CONFIRMED");
        }

        @Test
        @DisplayName("Multi-sensor: contributing sensors tracked")
        void multiSensorBookkeeping() {
            Track t = makeTrack();
            SimpleMatrix z = SENSOR.h(t.getEkf().getState());
            MeasurementModel sensor2 = new MeasurementModel(100, 100, 60, 0.012);

            t.update(z, SENSOR, "ALPHA", 1000);
            t.update(z, sensor2, "BRAVO", 2000);

            assertTrue(t.getContributingSensors().contains("ALPHA"));
            assertTrue(t.getContributingSensors().contains("BRAVO"));
        }

        @Test
        @DisplayName("Uncertainty grows during coasting")
        void uncertaintyGrowsDuringCoasting() {
            Track t = makeTrack();
            SimpleMatrix z = SENSOR.h(t.getEkf().getState());
            t.update(z, SENSOR, "S1", 1000);
            t.update(z, SENSOR, "S1", 2000);

            double uncBefore = t.getPositionUncertainty();
            t.recordMiss(3000);
            t.recordMiss(4000);
            t.recordMiss(5000); // now coasting
            double uncAfter = t.getPositionUncertainty();

            assertTrue(uncAfter > uncBefore,
                    "Uncertainty should grow during coasting: %.1f -> %.1f"
                            .formatted(uncBefore, uncAfter));
        }
        @Test
        @DisplayName("Multiple sensors at the same timestamp count as one confirmation hit")
        void sameTimestampSensorsCountAsOneHit() {
            Track t = makeTrack();

            SimpleMatrix z = SENSOR.h(t.getEkf().getState());

            // Track starts with one creation hit.
            t.update(z, SENSOR, "S1", 1000);
            assertEquals(2, t.getConsecutiveHits());

            // Same observation timestamp from two additional sensors.
            t.update(z, SENSOR, "S2", 1000);
            t.update(z, SENSOR, "S3", 1000);

            assertEquals(
                    2,
                    t.getConsecutiveHits(),
                    "Same timestamp must count as one lifecycle hit"
            );

            assertEquals(
                    TrackState.TENTATIVE,
                    t.getState(),
                    "Track must not confirm from three sensors in one time cycle"
            );

            // Next actual time cycle provides the third hit.
            t.update(z, SENSOR, "S1", 2000);

            assertEquals(
                    TrackState.CONFIRMED,
                    t.getState()
            );
        }
    }
}
