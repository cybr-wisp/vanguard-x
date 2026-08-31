package com.vanguard.spatial;

import java.util.*;

/**
 * Edge-triggered alert state machine. Tracks the current zone classification
 * for each (trackId, zoneId) pair and emits events only when the state changes.
 *
 * Key invariant: repeated identical inputs do NOT generate duplicate alerts.
 * A track sitting inside a breach zone emits exactly one ZONE_ENTRY event.
 */
public class AlertStateMachine {

    /**
     * Composite key for per-track, per-zone state.
     */
    private record StateKey(String trackId, String zoneId) {}

    private final Map<StateKey, ZoneClassification> currentState = new HashMap<>();

    /**
     * Update the classification for a track/zone pair and return any
     * transition event. Returns empty if the state hasn't changed.
     */
    public Optional<TrackEvent> update(String trackId, String zoneId,
                                        ZoneClassification newClassification,
                                        long timestampMs, double px, double py) {
        StateKey key = new StateKey(trackId, zoneId);
        ZoneClassification previous = currentState.getOrDefault(key, ZoneClassification.CLEAR);

        if (previous == newClassification) {
            return Optional.empty(); // no transition
        }

        currentState.put(key, newClassification);

        // Determine event type from the transition direction
        TrackEvent.EventType eventType = determineEventType(previous, newClassification);
        if (eventType == null) return Optional.empty();

        return Optional.of(new TrackEvent(
                eventType, trackId, zoneId, timestampMs,
                previous, newClassification, px, py));
    }

    /**
     * Determine the event type from a state transition.
     */
    private TrackEvent.EventType determineEventType(ZoneClassification from,
                                                      ZoneClassification to) {
        if (to == ZoneClassification.BREACH && from != ZoneClassification.BREACH) {
            return TrackEvent.EventType.ZONE_ENTRY;
        }
        if (from == ZoneClassification.BREACH && to != ZoneClassification.BREACH) {
            return TrackEvent.EventType.ZONE_EXIT;
        }
        if (from == ZoneClassification.CLEAR && to.ordinal() > ZoneClassification.CLEAR.ordinal()) {
            return TrackEvent.EventType.ZONE_APPROACH;
        }
        // Other transitions (e.g. ADVISORY -> WARNING) are severity changes
        // but not distinct event types in v1.0. Could be extended.
        return null;
    }

    /**
     * Get the current classification for a track/zone pair.
     */
    public ZoneClassification getCurrentState(String trackId, String zoneId) {
        return currentState.getOrDefault(new StateKey(trackId, zoneId), ZoneClassification.CLEAR);
    }

    /**
     * Remove state for a dropped track (cleanup).
     */
    public void removeTrack(String trackId) {
        currentState.entrySet().removeIf(e -> e.getKey().trackId().equals(trackId));
    }

    /** Number of tracked (track, zone) pairs. */
    public int getStateCount() { return currentState.size(); }
}
