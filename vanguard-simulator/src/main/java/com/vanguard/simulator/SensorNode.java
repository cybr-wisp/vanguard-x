package com.vanguard.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A sensor node sits at a fixed (sx, sy) position and observes ground-truth
 * targets by converting Cartesian displacement into noisy range/bearing.
 *
 * The raw report matches the Protobuf SensorReport contract:
 *   sensor_id, timestamp_ms, sensor_x, sensor_y, range, azimuth,
 *   signal_strength, sequence_number
 *
 * No canonical track ID crosses the wire. The hidden truth ID is carried
 * only for the evaluation harness.
 */
public class SensorNode {

    /**
     * A raw report ready for Protobuf serialization and UDP transmission.
     * {@code hiddenTruthId} and {@code sigmaRange/sigmaBearing} are metadata
     * not sent over the wire in the current proto, but available for evaluation
     * and for future proto upgrades.
     */
    public record RawReport(
            String  sensorId,
            long    sequenceNumber,
            long    timestampMs,
            double  sensorX,
            double  sensorY,
            double  rangeM,
            double  azimuthRad,
            double  signalStrength,
            // Metadata not in current proto but needed by EKF / eval
            double  sigmaRangeM,
            double  sigmaBearingRad,
            boolean isFalseDetection,
            String  hiddenTruthId
    ) {}

    private final ScenarioConfig.SensorSpec spec;
    private final NoiseModel noiseModel;
    private long sequenceCounter = 0;

    public SensorNode(ScenarioConfig.SensorSpec spec, Random rng) {
        this.spec = spec;
        this.noiseModel = new NoiseModel(
                rng, spec.sigmaRangeM(), spec.sigmaBearingRad(),
                spec.biasRangeM(), spec.biasBearingRad());
    }

    public String getSensorId() { return spec.sensorId(); }
    public double getSx()       { return spec.sx(); }
    public double getSy()       { return spec.sy(); }

    /**
     * Observe all active targets and produce raw reports. May also generate
     * false detections (clutter) based on the configured rate.
     */
    public List<RawReport> observe(List<TargetModel.TruthRecord> truthRecords,
                                   long observationMs, Random rng) {
        List<RawReport> reports = new ArrayList<>();

        for (TargetModel.TruthRecord truth : truthRecords) {
            double dx = truth.px() - spec.sx();
            double dy = truth.py() - spec.sy();
            double trueRange   = Math.sqrt(dx * dx + dy * dy);
            double trueBearing = Math.atan2(dy, dx);

            double noisyRange   = noiseModel.corruptRange(trueRange);
            double noisyBearing = noiseModel.corruptBearing(trueBearing);

            // Clamp negative range (physically impossible)
            if (noisyRange < 0) noisyRange = Math.abs(noisyRange);

            // Signal strength: inverse-square attenuation + small noise
            double snr = 1000.0 / (1.0 + trueRange * trueRange * 1e-6)
                    + rng.nextGaussian() * 0.5;

            reports.add(new RawReport(
                    spec.sensorId(), sequenceCounter++, observationMs,
                    spec.sx(), spec.sy(),
                    noisyRange, normalizeAngle(noisyBearing),
                    Math.max(0, snr),
                    spec.sigmaRangeM(), spec.sigmaBearingRad(),
                    false, truth.targetId()
            ));
        }

        // Possible false detection (clutter)
        if (spec.falseDetectionRate() > 0 && rng.nextDouble() < spec.falseDetectionRate()) {
            reports.add(new RawReport(
                    spec.sensorId(), sequenceCounter++, observationMs,
                    spec.sx(), spec.sy(),
                    500 + rng.nextDouble() * 5000,
                    rng.nextDouble() * 2 * Math.PI - Math.PI,
                    rng.nextDouble() * 2.0,   // weak signal
                    spec.sigmaRangeM(), spec.sigmaBearingRad(),
                    true, "__FALSE__"
            ));
        }

        return reports;
    }

    static double normalizeAngle(double a) {
        while (a >  Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
