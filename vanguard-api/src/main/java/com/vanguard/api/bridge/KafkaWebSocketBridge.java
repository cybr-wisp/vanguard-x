package com.vanguard.api.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanguard.api.repository.EventRepository;
import com.vanguard.api.repository.TrackRepository;
import com.vanguard.api.websocket.WebSocketConfig.EventStreamHandler;
import com.vanguard.api.websocket.WebSocketConfig.HealthStreamHandler;
import com.vanguard.api.websocket.WebSocketConfig.TrackStreamHandler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bridges Kafka topics to WebSocket broadcast and Redis persistence.
 *
 * Consumes:
 *   - tracks.fused   (fused track state from the tracking pipeline)
 *   - track-events   (zone transition events from the spatial pipeline)
 *
 * On each record:
 *   1. Deserializes the JSON payload
 *   2. Writes to Redis (TrackRepository / EventRepository) for REST queries
 *   3. Broadcasts JSON to connected WebSocket clients
 *
 * A separate scheduled method pushes health/metrics snapshots to /ws/health.
 */
@Component
@ConditionalOnProperty(name = "vanguard.demo.enabled", havingValue = "false")
public class KafkaWebSocketBridge {

    private static final Logger log = LoggerFactory.getLogger(KafkaWebSocketBridge.class);

    private static final String TRACKS_TOPIC = "tracks.fused";
    private static final String EVENTS_TOPIC = "track-events";

    private final TrackStreamHandler trackHandler;
    private final EventStreamHandler eventHandler;
    private final HealthStreamHandler healthHandler;
    private final TrackRepository trackRepo;
    private final EventRepository eventRepo;
    private final ObjectMapper mapper;

    @Value("${vanguard.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread trackThread;
    private Thread eventThread;

    // Live metrics for health broadcast
    private final LongAdder tracksConsumed = new LongAdder();
    private final LongAdder eventsConsumed = new LongAdder();
    private final AtomicInteger activeTracks = new AtomicInteger(0);
    private final AtomicInteger confirmedTracks = new AtomicInteger(0);
    private final AtomicInteger coastingTracks = new AtomicInteger(0);
    private final Map<String, String> trackStates = new ConcurrentHashMap<>();
    private final long startTimeMs = System.currentTimeMillis();

    // Throughput measurement
    private final LongAdder reportsInWindow = new LongAdder();
    private volatile double throughputPerSec = 0;
    private long windowStartMs = System.currentTimeMillis();

    // Latency tracking (measured from track timestamps)
    private final java.util.concurrent.ConcurrentLinkedDeque<Long> latencySamples = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile double measuredP50 = 0, measuredP95 = 0, measuredP99 = 0;
    private final LongAdder droppedCount = new LongAdder();

    public KafkaWebSocketBridge(TrackStreamHandler trackHandler,
                                 EventStreamHandler eventHandler,
                                 HealthStreamHandler healthHandler,
                                 TrackRepository trackRepo,
                                 EventRepository eventRepo,
                                 ObjectMapper mapper) {
        this.trackHandler = trackHandler;
        this.eventHandler = eventHandler;
        this.healthHandler = healthHandler;
        this.trackRepo = trackRepo;
        this.eventRepo = eventRepo;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running.set(true);

        trackThread = Thread.ofVirtual().name("kafka-track-bridge").start(this::consumeTracks);
        eventThread = Thread.ofVirtual().name("kafka-event-bridge").start(this::consumeEvents);

        log.info("KafkaWebSocketBridge started (bootstrap={})", bootstrapServers);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        log.info("KafkaWebSocketBridge stopping. tracks={} events={}",
                tracksConsumed.sum(), eventsConsumed.sum());
    }

    // ---- Track consumer ----

    private void consumeTracks() {
        Properties props = consumerProps("vanguard-api-tracks");
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TRACKS_TOPIC));
            log.info("Track bridge consumer subscribed to {}", TRACKS_TOPIC);

            while (running.get()) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;

                records.forEach(record -> {
                    try {
                        String json = new String(record.value());

                        // Parse for Redis persistence
                        @SuppressWarnings("unchecked")
                        Map<String, Object> track = mapper.readValue(json, Map.class);
                        String trackId = (String) track.get("trackId");
                        double px = toDouble(track.get("px"));
                        double py = toDouble(track.get("py"));
                        double vx = toDouble(track.get("vx"));
                        double vy = toDouble(track.get("vy"));
                        String state = (String) track.get("state");
                        double uncertainty = toDouble(track.get("uncertainty"));
                        long lastUpdateMs = toLong(track.get("lastUpdateMs"));

                        // Persist to Redis
                        if ("DROPPED".equals(state)) {
                            trackRepo.removeTrack(trackId);
                            trackStates.remove(trackId);
                        } else {
                            trackRepo.updateTrack(trackId, px, py, vx, vy,
                                    state, uncertainty, lastUpdateMs);
                            trackStates.put(trackId, state);
                        }

                        // Broadcast to WebSocket clients
                        trackHandler.broadcast(json);

                        tracksConsumed.increment();
                        reportsInWindow.increment();
                    } catch (Exception e) {
                        log.warn("Failed to process track record: {}", e.getMessage());
                    }
                });

                // Update track state counts
                int active = 0, confirmed = 0, coasting = 0;
                for (String s : trackStates.values()) {
                    active++;
                    if ("CONFIRMED".equals(s)) confirmed++;
                    else if ("COASTING".equals(s)) coasting++;
                }
                activeTracks.set(active);
                confirmedTracks.set(confirmed);
                coastingTracks.set(coasting);
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("Track bridge consumer crashed", e);
            }
        }
    }

    // ---- Event consumer ----

    private void consumeEvents() {
        Properties props = consumerProps("vanguard-api-events");
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(EVENTS_TOPIC));
            log.info("Event bridge consumer subscribed to {}", EVENTS_TOPIC);

            while (running.get()) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;

                records.forEach(record -> {
                    try {
                        String json = new String(record.value());

                        // Parse for Redis persistence
                        @SuppressWarnings("unchecked")
                        Map<String, Object> event = mapper.readValue(json, Map.class);
                        String eventId = (String) event.get("eventId");
                        String trackId = (String) event.get("trackId");
                        String zoneId = (String) event.get("zoneId");
                        String type = (String) event.get("type");
                        long timestampMs = toLong(event.get("timestampMs"));
                        double px = toDouble(event.get("px"));
                        double py = toDouble(event.get("py"));

                        // Persist to Redis
                        eventRepo.storeEvent(eventId, trackId, zoneId, type,
                                timestampMs, px, py);

                        // Broadcast to WebSocket clients
                        eventHandler.broadcast(json);

                        eventsConsumed.increment();
                    } catch (Exception e) {
                        log.warn("Failed to process event record: {}", e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("Event bridge consumer crashed", e);
            }
        }
    }

    // ---- Health metrics broadcast ----

    @Scheduled(fixedRate = 1500)
    public void broadcastHealth() {
        if (!running.get()) return;

        // Compute throughput
        long now = System.currentTimeMillis();
        long elapsed = now - windowStartMs;
        if (elapsed >= 2000) {
            throughputPerSec = reportsInWindow.sumThenReset() / (elapsed / 1000.0);
            windowStartMs = now;
        }

        try {
            // Compute latency percentiles from recent samples
            List<Long> samples = new ArrayList<>(latencySamples);
            Collections.sort(samples);
            if (!samples.isEmpty()) {
                measuredP50 = samples.get((int)(samples.size() * 0.50));
                measuredP95 = samples.get(Math.min(samples.size() - 1, (int)(samples.size() * 0.95)));
                measuredP99 = samples.get(Math.min(samples.size() - 1, (int)(samples.size() * 0.99)));
            }

            Map<String, Object> health = new LinkedHashMap<>();
            health.put("throughputReportsPerSec", Math.round(throughputPerSec));
            health.put("p50LatencyMs", measuredP50);
            health.put("p95LatencyMs", measuredP95);
            health.put("p99LatencyMs", measuredP99);
            health.put("activeTracks", activeTracks.get());
            health.put("confirmedTracks", confirmedTracks.get());
            health.put("coastingTracks", coastingTracks.get());
            health.put("queueDepth", latencySamples.size());
            health.put("kafkaLag", tracksConsumed.sum() > 0 ? Math.max(0, tracksConsumed.sum() - eventsConsumed.sum()) : 0);
            health.put("packetsDropped", droppedCount.sum());
            health.put("uptimeMs", now - startTimeMs);
            healthHandler.broadcast(mapper.writeValueAsString(health));
        } catch (Exception e) {
            log.warn("Failed to broadcast health: {}", e.getMessage());
        }
    }

    // ---- Helpers ----

    private Properties consumerProps(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return props;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }
}
