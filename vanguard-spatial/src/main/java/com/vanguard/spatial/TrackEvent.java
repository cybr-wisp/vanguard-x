package com.vanguard.spatial;

/**
 * An event emitted when a track's zone classification changes.
 * Events represent transitions, not frames. A track sitting inside
 * a zone does NOT emit thousands of duplicate breach events.
 */
public record TrackEvent(
        EventType type,
        String trackId,
        String zoneId,
        long timestampMs,
        ZoneClassification previousState,
        ZoneClassification newState,
        double px,
        double py
) {
    public enum EventType {
        /** Track entered the advisory buffer from CLEAR. */
        ZONE_APPROACH,
        /** Track crossed the zone boundary inward (ADVISORY/WARNING -> BREACH). */
        ZONE_ENTRY,
        /** Track crossed the zone boundary outward (BREACH -> WARNING/ADVISORY/CLEAR). */
        ZONE_EXIT
    }
}
