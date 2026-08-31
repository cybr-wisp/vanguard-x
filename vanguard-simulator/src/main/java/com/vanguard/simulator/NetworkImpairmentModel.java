package com.vanguard.simulator;

import java.util.*;

/**
 * Simulates network impairments on a stream of raw sensor reports:
 *   - Packet loss: report is dropped entirely
 *   - Duplication: report appears twice in the output
 *   - Jitter: report timestamp is shifted by a random offset
 *   - Reordering: reports within a configurable window are shuffled
 *
 * All randomness is seeded so impairments are fully reproducible.
 */
public class NetworkImpairmentModel {

    private final ScenarioConfig.ImpairmentSpec spec;
    private final Random rng;

    // Reorder buffer: accumulates reports within a window before flushing
    private final List<SensorNode.RawReport> reorderBuffer = new ArrayList<>();
    private long windowStartMs = -1;

    public NetworkImpairmentModel(ScenarioConfig.ImpairmentSpec spec, Random rng) {
        this.spec = spec;
        this.rng = rng;
    }

    /**
     * Process a batch of reports through the impairment pipeline. Returns the
     * (possibly modified) list of reports that survive. Order may change.
     */
    public List<SensorNode.RawReport> apply(List<SensorNode.RawReport> incoming) {
        if (!spec.enabled()) return new ArrayList<>(incoming);

        List<SensorNode.RawReport> afterLossAndDup = new ArrayList<>();

        for (SensorNode.RawReport r : incoming) {
            // --- Loss ---
            if (spec.packetLossRate() > 0 && rng.nextDouble() < spec.packetLossRate()) {
                continue; // dropped
            }

            // --- Jitter ---
            SensorNode.RawReport jittered = r;
            if (spec.jitterStdDevMs() > 0) {
                long jitterMs = (long)(rng.nextGaussian() * spec.jitterStdDevMs());
                long newTs = Math.max(0, r.timestampMs() + jitterMs);
                jittered = withTimestamp(r, newTs);
            }

            afterLossAndDup.add(jittered);

            // --- Duplication ---
            if (spec.duplicationRate() > 0 && rng.nextDouble() < spec.duplicationRate()) {
                afterLossAndDup.add(jittered); // exact duplicate
            }
        }

        // --- Reordering ---
        if (spec.reorderWindowMs() <= 0) {
            return afterLossAndDup;
        }

        List<SensorNode.RawReport> output = new ArrayList<>();
        for (SensorNode.RawReport r : afterLossAndDup) {
            if (windowStartMs < 0) windowStartMs = r.timestampMs();

            reorderBuffer.add(r);

            // Flush when the window is full
            if (r.timestampMs() - windowStartMs >= spec.reorderWindowMs()) {
                Collections.shuffle(reorderBuffer, rng);
                output.addAll(reorderBuffer);
                reorderBuffer.clear();
                windowStartMs = -1;
            }
        }
        return output;
    }

    /**
     * Flush any remaining reports in the reorder buffer (call at end of scenario).
     */
    public List<SensorNode.RawReport> flush() {
        if (reorderBuffer.isEmpty()) return List.of();
        Collections.shuffle(reorderBuffer, rng);
        List<SensorNode.RawReport> out = new ArrayList<>(reorderBuffer);
        reorderBuffer.clear();
        windowStartMs = -1;
        return out;
    }

    /** Package-private: statistics for testing. */
    record ImpairmentStats(int dropped, int duplicated, int reordered) {}

    private static SensorNode.RawReport withTimestamp(SensorNode.RawReport r, long newTs) {
        return new SensorNode.RawReport(
                r.sensorId(), r.sequenceNumber(), newTs,
                r.sensorX(), r.sensorY(),
                r.rangeM(), r.azimuthRad(), r.signalStrength(),
                r.sigmaRangeM(), r.sigmaBearingRad(),
                r.isFalseDetection(), r.hiddenTruthId()
        );
    }
}
