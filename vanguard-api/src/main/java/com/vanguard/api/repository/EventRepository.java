package com.vanguard.api.repository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.*;

/**
 * Redis repository for recent track events. Events are stored in a capped
 * list for chronological access and individually by ID for detail lookups.
 */
@Repository
public class EventRepository {

    private final StringRedisTemplate redis;

    public EventRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Store a track event.
     */
    public void storeEvent(String eventId, String trackId, String zoneId,
                            String eventType, long timestampMs,
                            double px, double py) {
        // Individual event hash
        String key = RedisKeySchema.eventKey(eventId);
        redis.opsForHash().putAll(key, Map.of(
                "eventId", eventId,
                "trackId", trackId,
                "zoneId", zoneId,
                "type", eventType,
                "timestampMs", String.valueOf(timestampMs),
                "px", String.valueOf(px),
                "py", String.valueOf(py)
        ));
        redis.expire(key, Duration.ofSeconds(RedisKeySchema.EVENT_TTL));

        // Append to recent events list (capped)
        String summary = "%s|%s|%s|%s|%d".formatted(eventId, trackId, zoneId, eventType, timestampMs);
        redis.opsForList().rightPush(RedisKeySchema.recentEventsKey(), summary);
        redis.opsForList().trim(RedisKeySchema.recentEventsKey(),
                -RedisKeySchema.MAX_RECENT_EVENTS, -1);
    }

    /**
     * Get details for one event.
     */
    public Map<Object, Object> getEvent(String eventId) {
        return redis.opsForHash().entries(RedisKeySchema.eventKey(eventId));
    }

    /**
     * Get the most recent N events (summaries).
     */
    public List<String> getRecentEvents(int count) {
        List<String> all = redis.opsForList().range(RedisKeySchema.recentEventsKey(), 0, -1);
        if (all == null) return List.of();
        int start = Math.max(0, all.size() - count);
        return all.subList(start, all.size());
    }
}
