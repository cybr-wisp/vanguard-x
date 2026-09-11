package com.vanguard.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the highest observed sequence number per sensor. Used to detect:
 *   - Duplicates: sequence <= last seen
 *   - Gaps: sequence > last seen + 1 (logged, not rejected)
 *
 * Thread-safe: updates for each sensor are performed atomically through
 * ConcurrentMap.compute().
 */
public class SequenceTracker {

    /** Per-sensor state. */
    private record SensorState(long highestSeq, long duplicateCount, long gapCount) {}

    private final ConcurrentMap<String, SensorState> state = new ConcurrentHashMap<>();

    public enum SequenceVerdict { ACCEPT, DUPLICATE, GAP_THEN_ACCEPT }

    /**
     * Atomically check a new report's sequence number against the sensor's
     * history. GAP_THEN_ACCEPT means there was a gap but the report is still
     * accepted (gaps are informational, not fatal).
     */
    public SequenceVerdict check(String sensorId, long sequenceNumber) {
        AtomicReference<SequenceVerdict> verdict = new AtomicReference<>();

        state.compute(sensorId, (ignored, current) -> {
            if (current == null) {
                verdict.set(SequenceVerdict.ACCEPT);
                return new SensorState(sequenceNumber, 0, 0);
            }

            if (sequenceNumber <= current.highestSeq()) {
                verdict.set(SequenceVerdict.DUPLICATE);
                return new SensorState(
                        current.highestSeq(),
                        current.duplicateCount() + 1,
                        current.gapCount()
                );
            }

            boolean hasGap = sequenceNumber > current.highestSeq() + 1;

            verdict.set(
                    hasGap
                            ? SequenceVerdict.GAP_THEN_ACCEPT
                            : SequenceVerdict.ACCEPT
            );

            return new SensorState(
                    sequenceNumber,
                    current.duplicateCount(),
                    current.gapCount() + (hasGap ? 1 : 0)
            );
        });

        return verdict.get();
    }

    public long getDuplicateCount(String sensorId) {
        SensorState s = state.get(sensorId);
        return s == null ? 0 : s.duplicateCount();
    }

    public long getGapCount(String sensorId) {
        SensorState s = state.get(sensorId);
        return s == null ? 0 : s.gapCount();
    }

    public void reset() {
        state.clear();
    }
}
