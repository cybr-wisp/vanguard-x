package com.vanguard.api.repository;

/**
 * Central Redis key schema. Redis holds live operational state, not
 * permanent historical truth. All keys use TTLs to self-expire.
 *
 * Key patterns:
 *   track:{trackId}           HASH  - current track state (px, py, vx, vy, state, uncertainty, lastUpdate)
 *   track:{trackId}:trail     LIST  - recent position trail (capped at 100 entries)
 *   tracks:active             SET   - set of all active (non-dropped) track IDs
 *   tracks:geo                GEO   - geospatial index of current track positions
 *   zone:{zoneId}             HASH  - zone definition and current status
 *   zones:active              SET   - set of all active zone IDs
 *   events:recent             LIST  - last N track events (capped at 500)
 *   event:{eventId}           HASH  - individual event details
 *   metrics:latest            HASH  - latest system metrics snapshot
 */
public final class RedisKeySchema {

    private RedisKeySchema() {}

    public static String trackKey(String trackId)      { return "track:" + trackId; }
    public static String trailKey(String trackId)      { return "track:" + trackId + ":trail"; }
    public static String activeTracksKey()             { return "tracks:active"; }
    public static String geoTracksKey()                { return "tracks:geo"; }
    public static String zoneKey(String zoneId)        { return "zone:" + zoneId; }
    public static String activeZonesKey()              { return "zones:active"; }
    public static String recentEventsKey()             { return "events:recent"; }
    public static String eventKey(String eventId)      { return "event:" + eventId; }
    public static String metricsKey()                  { return "metrics:latest"; }

    // TTLs in seconds
    public static final long TRACK_TTL        = 300;    // 5 minutes
    public static final long TRAIL_TTL        = 300;
    public static final long EVENT_TTL        = 600;    // 10 minutes
    public static final int  MAX_TRAIL_LENGTH = 100;
    public static final int  MAX_RECENT_EVENTS = 500;
}
