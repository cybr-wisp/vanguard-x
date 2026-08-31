package com.vanguard.api.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis repository for live track state. A restarted API can reconstruct
 * the current operational picture from Redis without re-running the simulator.
 */
@Repository
public class TrackRepository {

    private final StringRedisTemplate redis;

    public TrackRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Update a track's live state in Redis.
     */
    public void updateTrack(String trackId, double px, double py,
                             double vx, double vy, String state,
                             double uncertainty, long lastUpdateMs) {
        String key = RedisKeySchema.trackKey(trackId);
        Map<String, String> fields = Map.of(
                "px", String.valueOf(px),
                "py", String.valueOf(py),
                "vx", String.valueOf(vx),
                "vy", String.valueOf(vy),
                "state", state,
                "uncertainty", String.valueOf(uncertainty),
                "lastUpdateMs", String.valueOf(lastUpdateMs)
        );
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, Duration.ofSeconds(RedisKeySchema.TRACK_TTL));

        // Update active set
        redis.opsForSet().add(RedisKeySchema.activeTracksKey(), trackId);

        // Update geo index
        redis.opsForGeo().add(RedisKeySchema.geoTracksKey(),
                new org.springframework.data.geo.Point(px, py), trackId);

        // Append to trail (capped)
        String trailKey = RedisKeySchema.trailKey(trackId);
        String trailEntry = "%.2f,%.2f,%d".formatted(px, py, lastUpdateMs);
        redis.opsForList().rightPush(trailKey, trailEntry);
        redis.opsForList().trim(trailKey, -RedisKeySchema.MAX_TRAIL_LENGTH, -1);
        redis.expire(trailKey, Duration.ofSeconds(RedisKeySchema.TRAIL_TTL));
    }

    /**
     * Remove a dropped track from the active set and geo index.
     */
    public void removeTrack(String trackId) {
        redis.opsForSet().remove(RedisKeySchema.activeTracksKey(), trackId);
        redis.opsForGeo().remove(RedisKeySchema.geoTracksKey(), trackId);
        redis.delete(RedisKeySchema.trackKey(trackId));
        redis.delete(RedisKeySchema.trailKey(trackId));
    }

    /**
     * Get current state of one track.
     */
    public Map<Object, Object> getTrack(String trackId) {
        return redis.opsForHash().entries(RedisKeySchema.trackKey(trackId));
    }

    /**
     * Get all active track IDs.
     */
    public Set<String> getActiveTrackIds() {
        Set<String> ids = redis.opsForSet().members(RedisKeySchema.activeTracksKey());
        return ids == null ? Set.of() : ids;
    }

    /**
     * Get all active tracks with their state.
     */
    public List<Map<String, String>> getAllActiveTracks() {
        Set<String> ids = getActiveTrackIds();
        List<Map<String, String>> tracks = new ArrayList<>();
        for (String id : ids) {
            Map<Object, Object> raw = getTrack(id);
            if (!raw.isEmpty()) {
                Map<String, String> track = new HashMap<>();
                track.put("trackId", id);
                raw.forEach((k, v) -> track.put(k.toString(), v.toString()));
                tracks.add(track);
            }
        }
        return tracks;
    }

    /**
     * Get the recent trail for a track.
     */
    public List<String> getTrail(String trackId) {
        List<String> trail = redis.opsForList().range(RedisKeySchema.trailKey(trackId), 0, -1);
        return trail == null ? List.of() : trail;
    }
}
