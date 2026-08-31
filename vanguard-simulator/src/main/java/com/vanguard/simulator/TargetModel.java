package com.vanguard.simulator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single synthetic target with a ground-truth trajectory composed of ordered
 * segments: constant-velocity, constant-acceleration, and coordinated turns.
 *
 * All positions use a local 2D Cartesian frame (meters). Time is in milliseconds
 * since scenario epoch to match the Protobuf contract (timestamp_ms).
 *
 * The hidden ground-truth ID is retained for evaluation only; it is never
 * transmitted over the wire to the tracking pipeline.
 */
public class TargetModel {

    /**
     * Immutable snapshot of ground-truth state at a single instant.
     */
    public record TruthRecord(
            String targetId,
            long timestampMs,
            double px,     // position x (m)
            double py,     // position y (m)
            double vx,     // velocity x (m/s)
            double vy      // velocity y (m/s)
    ) {}

    // ---- Segment types ----

    public sealed interface Segment {
        long startMs();
        long endMs();

        /** State [px, py, vx, vy] at time t within [startMs, endMs]. */
        double[] stateAt(long tMs);
    }

    /** Constant-velocity: no acceleration. */
    public record StraightSegment(
            long startMs, long endMs,
            double px0, double py0, double vx, double vy
    ) implements Segment {
        @Override
        public double[] stateAt(long tMs) {
            double dt = (tMs - startMs) / 1000.0;
            return new double[]{ px0 + vx * dt, py0 + vy * dt, vx, vy };
        }
    }

    /** Constant acceleration: linear velocity ramp. */
    public record AccelerationSegment(
            long startMs, long endMs,
            double px0, double py0, double vx0, double vy0,
            double ax, double ay
    ) implements Segment {
        @Override
        public double[] stateAt(long tMs) {
            double dt = (tMs - startMs) / 1000.0;
            return new double[]{
                    px0 + vx0 * dt + 0.5 * ax * dt * dt,
                    py0 + vy0 * dt + 0.5 * ay * dt * dt,
                    vx0 + ax * dt,
                    vy0 + ay * dt
            };
        }
    }

    /** Coordinated turn: constant speed, constant turn rate omega (rad/s). */
    public record TurnSegment(
            long startMs, long endMs,
            double px0, double py0, double vx0, double vy0,
            double omegaRadPerSec
    ) implements Segment {
        @Override
        public double[] stateAt(long tMs) {
            double dt = (tMs - startMs) / 1000.0;
            double w = omegaRadPerSec;

            if (Math.abs(w) < 1e-12) {
                return new double[]{ px0 + vx0 * dt, py0 + vy0 * dt, vx0, vy0 };
            }

            double sinWt = Math.sin(w * dt);
            double cosWt = Math.cos(w * dt);

            return new double[]{
                    px0 + (vx0 * sinWt - vy0 * (1 - cosWt)) / w,
                    py0 + (vx0 * (1 - cosWt) + vy0 * sinWt) / w,
                    vx0 * cosWt - vy0 * sinWt,
                    vx0 * sinWt + vy0 * cosWt
            };
        }
    }

    // ---- Instance ----

    private final String targetId;
    private final List<Segment> segments;

    public TargetModel(String targetId, List<Segment> segments) {
        this.targetId = targetId;
        this.segments = List.copyOf(segments);
        validateContinuity();
    }

    private void validateContinuity() {
        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i).startMs() != segments.get(i - 1).endMs()) {
                throw new IllegalArgumentException(
                        "Segment gap between index " + (i - 1) + " and " + i
                                + " for target " + targetId);
            }
        }
    }

    public String getTargetId()  { return targetId; }
    public long   getStartMs()   { return segments.isEmpty() ? 0 : segments.getFirst().startMs(); }
    public long   getEndMs()     { return segments.isEmpty() ? 0 : segments.getLast().endMs(); }
    public List<Segment> getSegments() { return segments; }

    /** Ground-truth state at time t, or null if the target is not active. */
    public TruthRecord stateAt(long tMs) {
        for (Segment seg : segments) {
            if (tMs >= seg.startMs() && tMs <= seg.endMs()) {
                double[] s = seg.stateAt(tMs);
                return new TruthRecord(targetId, tMs, s[0], s[1], s[2], s[3]);
            }
        }
        return null;
    }

    /** Sample the full trajectory at a fixed interval. */
    public List<TruthRecord> sampleTrajectory(long intervalMs) {
        if (segments.isEmpty()) return Collections.emptyList();
        List<TruthRecord> out = new ArrayList<>();
        for (long t = getStartMs(); t <= getEndMs(); t += intervalMs) {
            TruthRecord r = stateAt(t);
            if (r != null) out.add(r);
        }
        return out;
    }

    // ---- Fluent builder ----

    public static Builder builder(String targetId) { return new Builder(targetId); }

    public static class Builder {
        private final String targetId;
        private final List<Segment> segments = new ArrayList<>();
        private double px, py, vx, vy;
        private long currentMs;

        Builder(String targetId) { this.targetId = targetId; }

        public Builder initialState(long startMs, double px, double py, double vx, double vy) {
            this.currentMs = startMs; this.px = px; this.py = py; this.vx = vx; this.vy = vy;
            return this;
        }

        public Builder straight(double durationSec) {
            long endMs = currentMs + (long)(durationSec * 1000);
            segments.add(new StraightSegment(currentMs, endMs, px, py, vx, vy));
            advance(endMs);
            return this;
        }

        public Builder accelerate(double durationSec, double ax, double ay) {
            long endMs = currentMs + (long)(durationSec * 1000);
            segments.add(new AccelerationSegment(currentMs, endMs, px, py, vx, vy, ax, ay));
            advance(endMs);
            return this;
        }

        public Builder turn(double durationSec, double omegaRadPerSec) {
            long endMs = currentMs + (long)(durationSec * 1000);
            segments.add(new TurnSegment(currentMs, endMs, px, py, vx, vy, omegaRadPerSec));
            advance(endMs);
            return this;
        }

        private void advance(long endMs) {
            double[] s = segments.getLast().stateAt(endMs);
            px = s[0]; py = s[1]; vx = s[2]; vy = s[3];
            currentMs = endMs;
        }

        public TargetModel build() { return new TargetModel(targetId, segments); }
    }
}
