package com.vanguard.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks the highest observed sequence number per sensor. Used to detect:
 *   - Duplicates: sequence <= last seen
 *   - Gaps: sequence > last seen + 1 (logged, not rejected)
 *
 * Thread-safe: one instance shared across the Netty event loop (single-
 * threaded per channel) and any downstream handoff.
 */
public class SequenceTracker {

    /** Per-sensor state. */
    private record SensorState(long highestSeq, long duplicateCount, long gapCount) {}

    private final ConcurrentMap<String, SensorState> state = new ConcurrentHashMap<>();

    public enum SequenceVerdict { ACCEPT, DUPLICATE, GAP_THEN_ACCEPT }

    /**
     * Check a new report's sequence number against the sensor's history.
     * Returns the verdict. GAP_THEN_ACCEPT means there was a gap but the
     * report is still accepted (gaps are informational, not fatal).
     */
    public SequenceVerdict check(String sensorId, long sequenceNumber) {
        SensorState current = state.get(sensorId);

        if (current == null) {
            // First report from this sensor
            state.put(sensorId, new SensorState(sequenceNumber, 0, 0));
            return SequenceVerdict.ACCEPT;
        }

        if (sequenceNumber <= current.highestSeq()) {
            // Duplicate or replay
            state.put(sensorId, new SensorState(
                    current.highestSeq(),
                    current.duplicateCount() + 1,
                    current.gapCount()));
            return SequenceVerdict.DUPLICATE;
        }

        boolean hasGap = sequenceNumber > current.highestSeq() + 1;
        state.put(sensorId, new SensorState(
                sequenceNumber,
                current.duplicateCount(),
                current.gapCount() + (hasGap ? 1 : 0)));

        return hasGap ? SequenceVerdict.GAP_THEN_ACCEPT : SequenceVerdict.ACCEPT;
    }

    /** Get the number of duplicates detected for a sensor. */
    public long getDuplicateCount(String sensorId) {
        SensorState s = state.get(sensorId);
        return s == null ? 0 : s.duplicateCount();
    }

    /** Get the number of gaps detected for a sensor. */
    public long getGapCount(String sensorId) {
        SensorState s = state.get(sensorId);
        return s == null ? 0 : s.gapCount();
    }

    /** Reset all state (testing only). */
    public void reset() {
        state.clear();
    }
}
