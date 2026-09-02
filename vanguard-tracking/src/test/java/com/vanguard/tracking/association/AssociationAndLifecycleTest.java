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

            Track track =
                    manager.getAllTracks()
                            .iterator()
                            .next();

            manager.processObservations(
                    List.of(),
                    SENSOR,
                    "S1",
                    2000
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

            /*
             * Timestamp 2000 remains one open observation cycle.
             * Moving to 3000 finalizes it exactly once.
             */
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
        }
    }
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
