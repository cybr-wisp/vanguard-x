package com.vanguard.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldSimulatorTest {

    // ---- Day 2 exit gate: identical seeds produce identical ground truth ----

    @Test
    @DisplayName("Identical seeds produce identical trajectories")
    void identicalSeedsProduceIdenticalTrajectories() {
        ScenarioConfig config = ScenarioLoader.referenceScenario();

        WorldSimulator sim1 = WorldSimulator.fromConfig(config);
        WorldSimulator sim2 = WorldSimulator.fromConfig(config);

        List<TargetModel.TruthRecord> traj1 = sim1.generateFullTrajectories(1000);
        List<TargetModel.TruthRecord> traj2 = sim2.generateFullTrajectories(1000);

        assertEquals(traj1.size(), traj2.size(), "Same number of truth records");

        for (int i = 0; i < traj1.size(); i++) {
            TargetModel.TruthRecord a = traj1.get(i);
            TargetModel.TruthRecord b = traj2.get(i);
            assertEquals(a.targetId(), b.targetId());
            assertEquals(a.timestampMs(), b.timestampMs());
            assertEquals(a.px(), b.px(), 1e-12, "px mismatch at index " + i);
            assertEquals(a.py(), b.py(), 1e-12, "py mismatch at index " + i);
            assertEquals(a.vx(), b.vx(), 1e-12, "vx mismatch at index " + i);
            assertEquals(a.vy(), b.vy(), 1e-12, "vy mismatch at index " + i);
        }
    }

    @Test
    @DisplayName("Reference scenario has at least 20 targets")
    void referenceScenarioHasEnoughTargets() {
        ScenarioConfig config = ScenarioLoader.referenceScenario();
        WorldSimulator sim = WorldSimulator.fromConfig(config);
        assertTrue(sim.getTargetCount() >= 20,
                "Expected >= 20 targets, got " + sim.getTargetCount());
    }

    @Test
    @DisplayName("Late-arrival targets are not active at t=0")
    void lateArrivalsNotActiveAtZero() {
        ScenarioConfig config = ScenarioLoader.referenceScenario();
        WorldSimulator sim = WorldSimulator.fromConfig(config);

        List<TargetModel.TruthRecord> atZero = sim.truthAt(0);
        // Late arrivals (TGT-L*) start at t > 0
        long lateCount = atZero.stream()
                .filter(r -> r.targetId().startsWith("TGT-L"))
                .count();
        assertEquals(0, lateCount, "Late arrivals should not appear at t=0");
    }

    @Test
    @DisplayName("Late arrivals become active at their start time")
    void lateArrivalsAppearAtStartTime() {
        ScenarioConfig config = ScenarioLoader.referenceScenario();
        WorldSimulator sim = WorldSimulator.fromConfig(config);

        // TGT-L01 starts at 20 seconds = 20000 ms
        List<TargetModel.TruthRecord> at20s = sim.truthAt(20_000);
        assertTrue(at20s.stream().anyMatch(r -> r.targetId().equals("TGT-L01")),
                "TGT-L01 should be active at its start time");
    }

    // ---- Segment math correctness ----

    @Test
    @DisplayName("Straight segment: position = initial + velocity * time")
    void straightSegmentKinematics() {
        TargetModel target = TargetModel.builder("test-straight")
                .initialState(0, 100, 200, 10, -5)
                .straight(10) // 10 seconds
                .build();

        TargetModel.TruthRecord at5s = target.stateAt(5000);
        assertNotNull(at5s);
        assertEquals(150.0, at5s.px(), 1e-9);   // 100 + 10*5
        assertEquals(175.0, at5s.py(), 1e-9);   // 200 + (-5)*5
        assertEquals(10.0,  at5s.vx(), 1e-9);
        assertEquals(-5.0,  at5s.vy(), 1e-9);
    }

    @Test
    @DisplayName("Acceleration segment: v = v0 + a*t, p = p0 + v0*t + 0.5*a*t^2")
    void accelerationSegmentKinematics() {
        TargetModel target = TargetModel.builder("test-accel")
                .initialState(0, 0, 0, 10, 0)
                .accelerate(10, 2, 0) // ax=2 m/s^2, 10 seconds
                .build();

        TargetModel.TruthRecord at10s = target.stateAt(10_000);
        assertNotNull(at10s);
        assertEquals(200.0, at10s.px(), 1e-9);  // 10*10 + 0.5*2*100
        assertEquals(0.0,   at10s.py(), 1e-9);
        assertEquals(30.0,  at10s.vx(), 1e-9);  // 10 + 2*10
        assertEquals(0.0,   at10s.vy(), 1e-9);
    }

    @Test
    @DisplayName("Turn segment preserves speed")
    void turnSegmentPreservesSpeed() {
        double vx0 = 20.0, vy0 = 0.0;
        double speed0 = Math.sqrt(vx0 * vx0 + vy0 * vy0);

        TargetModel target = TargetModel.builder("test-turn")
                .initialState(0, 0, 0, vx0, vy0)
                .turn(30, 0.1) // omega = 0.1 rad/s, 30 seconds (full 3 rad turn)
                .build();

        // Check speed at several points
        for (long t = 0; t <= 30_000; t += 5000) {
            TargetModel.TruthRecord r = target.stateAt(t);
            assertNotNull(r);
            double speed = Math.sqrt(r.vx() * r.vx() + r.vy() * r.vy());
            assertEquals(speed0, speed, 1e-9,
                    "Speed should be constant during coordinated turn at t=" + t);
        }
    }

    @Test
    @DisplayName("Multi-segment trajectory has continuous position at boundaries")
    void multiSegmentContinuity() {
        TargetModel target = TargetModel.builder("test-multi")
                .initialState(0, 0, 0, 15, 10)
                .straight(10)
                .accelerate(5, 1, -1)
                .turn(8, 0.05)
                .straight(7)
                .build();

        // Check that position is continuous at each segment boundary
        List<TargetModel.Segment> segs = target.getSegments();
        for (int i = 0; i < segs.size() - 1; i++) {
            long boundary = segs.get(i).endMs();
            double[] endOfPrev = segs.get(i).stateAt(boundary);
            double[] startOfNext = segs.get(i + 1).stateAt(boundary);

            assertEquals(endOfPrev[0], startOfNext[0], 1e-9,
                    "px discontinuity at boundary " + i);
            assertEquals(endOfPrev[1], startOfNext[1], 1e-9,
                    "py discontinuity at boundary " + i);
            assertEquals(endOfPrev[2], startOfNext[2], 1e-9,
                    "vx discontinuity at boundary " + i);
            assertEquals(endOfPrev[3], startOfNext[3], 1e-9,
                    "vy discontinuity at boundary " + i);
        }
    }

    @Test
    @DisplayName("Segment gap throws IllegalArgumentException")
    void segmentGapThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new TargetModel("bad", List.of(
                        new TargetModel.StraightSegment(0, 5000, 0, 0, 1, 0),
                        new TargetModel.StraightSegment(6000, 10000, 5, 0, 1, 0) // gap at 5000-6000
                )));
    }

    @Test
    @DisplayName("Target returns null for time outside active interval")
    void nullOutsideActiveInterval() {
        TargetModel target = TargetModel.builder("test-bounds")
                .initialState(5000, 0, 0, 1, 0)
                .straight(10)
                .build();

        assertNull(target.stateAt(0));      // before start
        assertNull(target.stateAt(20_000)); // after end
        assertNotNull(target.stateAt(10_000)); // within
    }
}
