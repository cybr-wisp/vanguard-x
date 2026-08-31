package com.vanguard.simulator;

import com.vanguard.simulator.ScenarioConfig.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds {@link ScenarioConfig} instances programmatically.
 * YAML-based loading (Jackson/SnakeYAML) plugs in later without
 * changing the domain model.
 */
public final class ScenarioLoader {

    private ScenarioLoader() {}

    /**
     * Reference scenario: 22 deterministic targets across 5 groups exercising
     * every segment type. Sensor placement gives overlapping but imperfect coverage.
     */
    public static ScenarioConfig referenceScenario() {
        long seed = 42L;
        Random rng = new Random(seed);
        long durationMs = 120_000L; // 120 s

        List<TargetSpec> targets = new ArrayList<>();

        // Group 1 - north-south corridor, mostly straight (5 targets)
        for (int i = 0; i < 5; i++) {
            String id = "TGT-N%02d".formatted(i + 1);
            double px0 = -2000 + rng.nextDouble() * 500;
            double py0 = -3000 + i * 600.0;
            double vx0 = 15 + rng.nextDouble() * 10;
            double vy0 = -5 + rng.nextDouble() * 10;
            targets.add(new TargetSpec(id, 0, px0, py0, vx0, vy0, List.of(
                    SegmentSpec.straight(40),
                    SegmentSpec.accelerate(10, 2.0, 0),
                    SegmentSpec.straight(70)
            )));
        }

        // Group 2 - turning targets (5)
        for (int i = 0; i < 5; i++) {
            String id = "TGT-T%02d".formatted(i + 1);
            double px0 = 1000 + rng.nextDouble() * 1000;
            double py0 = 1000 + i * 500.0;
            double speed = 20 + rng.nextDouble() * 10;
            double heading = rng.nextDouble() * 2 * Math.PI;
            double omega = (rng.nextBoolean() ? 1 : -1) * (0.03 + rng.nextDouble() * 0.05);
            targets.add(new TargetSpec(id, 0,
                    px0, py0, speed * Math.cos(heading), speed * Math.sin(heading),
                    List.of(
                            SegmentSpec.straight(20),
                            SegmentSpec.turn(30, omega),
                            SegmentSpec.straight(30),
                            SegmentSpec.turn(20, -omega),
                            SegmentSpec.straight(20)
                    )));
        }

        // Group 3 - fast movers with accel changes (5)
        for (int i = 0; i < 5; i++) {
            String id = "TGT-F%02d".formatted(i + 1);
            double px0 = -4000 + rng.nextDouble() * 500;
            double py0 = 2000 + i * 400.0;
            double vx0 = 30 + rng.nextDouble() * 15;
            double vy0 = rng.nextDouble() * 5;
            targets.add(new TargetSpec(id, 0, px0, py0, vx0, vy0, List.of(
                    SegmentSpec.straight(15),
                    SegmentSpec.accelerate(10, 3.0, 1.5),
                    SegmentSpec.straight(20),
                    SegmentSpec.accelerate(10, -2.0, 0),
                    SegmentSpec.straight(25),
                    SegmentSpec.turn(15, 0.04),
                    SegmentSpec.straight(25)
                    )));
        }

        // Group 4 - slow movers converging on center (association stress) (4)
        for (int i = 0; i < 4; i++) {
            String id = "TGT-S%02d".formatted(i + 1);
            double angle = i * Math.PI / 2.0 + Math.PI / 4.0;
            double px0 = 500 * Math.cos(angle);
            double py0 = 500 * Math.sin(angle);
            double speed = 8 + rng.nextDouble() * 4;
            targets.add(new TargetSpec(id, 0, px0, py0,
                    -speed * Math.cos(angle), -speed * Math.sin(angle),
                    List.of(
                            SegmentSpec.straight(30),
                            SegmentSpec.turn(20, 0.06),
                            SegmentSpec.straight(40),
                            SegmentSpec.turn(15, -0.04),
                            SegmentSpec.straight(15)
                    )));
        }

        // Group 5 - late arrivals, staggered start (3)
        for (int i = 0; i < 3; i++) {
            String id = "TGT-L%02d".formatted(i + 1);
            long startMs = (20 + i * 15) * 1000L;
            double px0 = -3000 + rng.nextDouble() * 1000;
            double py0 = -1000 + rng.nextDouble() * 2000;
            double vx0 = 18 + rng.nextDouble() * 12;
            double vy0 = rng.nextDouble() * 8 - 4;
            double halfDur = (durationMs - startMs) / 2000.0; // seconds
            targets.add(new TargetSpec(id, startMs, px0, py0, vx0, vy0, List.of(
                    SegmentSpec.straight(halfDur),
                    SegmentSpec.turn(halfDur, 0.03)
            )));
        }

        // Sensors - three positions with different noise profiles
        List<SensorSpec> sensors = List.of(
                new SensorSpec("SENSOR-ALPHA",    -2000,     0,   50, 0.010, 5.0, 0.002, 100, 0.01),
                new SensorSpec("SENSOR-BRAVO",     2000, -1500,   75, 0.015,-3.0, 0.001, 100, 0.02),
                new SensorSpec("SENSOR-CHARLIE",      0,  2500,   60, 0.012, 2.0,-0.001, 100, 0.015)
        );

        ImpairmentSpec impairments = new ImpairmentSpec(0.02, 0.01, 50, 200, true);

        return new ScenarioConfig("reference-22-targets", seed, durationMs, targets, sensors, impairments);
    }

    /** Minimal scenario for fast unit tests: 2 targets, 1 sensor, no impairments. */
    public static ScenarioConfig minimalScenario() {
        return new ScenarioConfig("minimal-test", 123L, 30_000L,
                List.of(
                        new TargetSpec("TGT-01", 0, 0, 0, 10, 5,
                                List.of(SegmentSpec.straight(30))),
                        new TargetSpec("TGT-02", 0, 500, 500, -8, 3,
                                List.of(SegmentSpec.straight(15), SegmentSpec.turn(15, 0.05)))
                ),
                List.of(new SensorSpec("SENSOR-TEST", 0, 0, 30, 0.008, 0, 0, 100, 0)),
                ImpairmentSpec.none()
        );
    }
}
