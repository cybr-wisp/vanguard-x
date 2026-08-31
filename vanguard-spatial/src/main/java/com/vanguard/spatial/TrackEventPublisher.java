package com.vanguard.spatial;

import java.util.*;

/**
 * Orchestrates geofence evaluation and event publishing for all active tracks.
 * For each processing cycle:
 *   1. Classify each track against all zones
 *   2. Feed classifications into the AlertStateMachine
 *   3. Collect transition events
 *
 * Downstream consumers (Kafka, WebSocket) receive only transition events,
 * never per-frame repeated classifications.
 */
public class TrackEventPublisher {

    private final GeofenceEngine geofenceEngine;
    private final AlertStateMachine stateMachine;
    private final List<TrackEventListener> listeners = new ArrayList<>();

    public TrackEventPublisher(GeofenceEngine geofenceEngine) {
        this.geofenceEngine = geofenceEngine;
        this.stateMachine = new AlertStateMachine();
    }

    /**
     * Listener interface for downstream event consumers.
     */
    @FunctionalInterface
    public interface TrackEventListener {
        void onEvent(TrackEvent event);
    }

    public void addListener(TrackEventListener listener) {
        listeners.add(listener);
    }

    /**
     * Evaluate one track's position against all zones and emit any transition events.
     *
     * @return list of events emitted (may be empty)
     */
    public List<TrackEvent> evaluateTrack(String trackId, double px, double py, long timestampMs) {
        Map<String, ZoneClassification> classifications = geofenceEngine.classify(px, py);
        List<TrackEvent> events = new ArrayList<>();

        for (var entry : classifications.entrySet()) {
            Optional<TrackEvent> event = stateMachine.update(
                    trackId, entry.getKey(), entry.getValue(),
                    timestampMs, px, py);
            event.ifPresent(e -> {
                events.add(e);
                for (TrackEventListener listener : listeners) {
                    listener.onEvent(e);
                }
            });
        }

        return events;
    }

    /**
     * Clean up state for a dropped track.
     */
    public void removeTrack(String trackId) {
        stateMachine.removeTrack(trackId);
    }

    public AlertStateMachine getStateMachine() { return stateMachine; }
}
