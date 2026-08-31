package com.vanguard.simulator;

import java.util.List;

/**
 * Immutable configuration for a simulation scenario.
 * Designed for deserialization from YAML via Jackson.
 */
public record ScenarioConfig(
        String name,
        long seed,
        long scenarioDurationMs,
        List<TargetSpec> targets,
        List<SensorSpec> sensors,
        ImpairmentSpec impairments
) {

    public enum SegmentType { STRAIGHT, ACCELERATE, TURN }

    public record SegmentSpec(
            SegmentType type,
            double durationSec,
            double ax,               // ACCELERATE only
            double ay,               // ACCELERATE only
            double omegaRadPerSec    // TURN only
    ) {
        public static SegmentSpec straight(double sec) {
            return new SegmentSpec(SegmentType.STRAIGHT, sec, 0, 0, 0);
        }
        public static SegmentSpec accelerate(double sec, double ax, double ay) {
            return new SegmentSpec(SegmentType.ACCELERATE, sec, ax, ay, 0);
        }
        public static SegmentSpec turn(double sec, double omega) {
            return new SegmentSpec(SegmentType.TURN, sec, 0, 0, omega);
        }
    }

    public record TargetSpec(
            String id,
            long startMs,
            double px0, double py0,
            double vx0, double vy0,
            List<SegmentSpec> segments
    ) {}

    public record SensorSpec(
            String sensorId,
            double sx,                   // sensor x (m)
            double sy,                   // sensor y (m)
            double sigmaRangeM,          // range noise std dev (m)
            double sigmaBearingRad,      // bearing noise std dev (rad)
            double biasRangeM,           // systematic range bias (m)
            double biasBearingRad,       // systematic bearing bias (rad)
            long   reportIntervalMs,     // observation cadence
            double falseDetectionRate    // probability of clutter per tick
    ) {}

    public record ImpairmentSpec(
            double packetLossRate,
            double duplicationRate,
            long   jitterStdDevMs,
            long   reorderWindowMs,
            boolean enabled
    ) {
        public static ImpairmentSpec none() {
            return new ImpairmentSpec(0, 0, 0, 0, false);
        }
    }
}
