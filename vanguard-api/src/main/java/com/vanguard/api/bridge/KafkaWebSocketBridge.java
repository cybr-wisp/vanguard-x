package com.vanguard.api.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanguard.api.pipeline.PipelineOrchestrator;
import com.vanguard.api.repository.EventRepository;
import com.vanguard.api.repository.TrackRepository;
import com.vanguard.api.websocket.WebSocketConfig.EventStreamHandler;
import com.vanguard.api.websocket.WebSocketConfig.HealthStreamHandler;
import com.vanguard.api.websocket.WebSocketConfig.TrackStreamHandler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Component
@ConditionalOnProperty(name = "vanguard.demo.enabled", havingValue = "false")
public class KafkaWebSocketBridge {

    private static final Logger log =
            LoggerFactory.getLogger(KafkaWebSocketBridge.class);

    private static final String TRACKS_TOPIC = "tracks.fused";
    private static final String EVENTS_TOPIC = "track-events";

    private static final int MAX_LATENCY_SAMPLES = 2048;

    private final TrackStreamHandler trackHandler;
    private final EventStreamHandler eventHandler;
    private final HealthStreamHandler healthHandler;
    private final TrackRepository trackRepo;
    private final EventRepository eventRepo;
    private final ObjectMapper mapper;
    private final PipelineOrchestrator pipeline;

    @Value("${vanguard.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread trackThread;
    private Thread eventThread;

    private final LongAdder tracksConsumed = new LongAdder();
    private final LongAdder eventsConsumed = new LongAdder();

    private final AtomicInteger activeTracks = new AtomicInteger();
    private final AtomicInteger confirmedTracks = new AtomicInteger();
    private final AtomicInteger coastingTracks = new AtomicInteger();

    private final Map<String, String> trackStates =
            new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<Long> latencySamples =
            new ConcurrentLinkedDeque<>();

    private final AtomicInteger inFlightRecords =
            new AtomicInteger();

    private final AtomicLong trackKafkaLag =
            new AtomicLong();

    private final AtomicLong eventKafkaLag =
            new AtomicLong();

    private final long startTimeMs =
            System.currentTimeMillis();

    private volatile double throughputPerSec;
    private volatile double measuredP50;
    private volatile double measuredP95;
    private volatile double measuredP99;

    private long windowStartMs =
            System.currentTimeMillis();

    private long lastGatewayAccepted;

    public KafkaWebSocketBridge(
            TrackStreamHandler trackHandler,
            EventStreamHandler eventHandler,
            HealthStreamHandler healthHandler,
            TrackRepository trackRepo,
            EventRepository eventRepo,
            ObjectMapper mapper,
            PipelineOrchestrator pipeline) {

        this.trackHandler = trackHandler;
        this.eventHandler = eventHandler;
        this.healthHandler = healthHandler;
        this.trackRepo = trackRepo;
        this.eventRepo = eventRepo;
        this.mapper = mapper;
        this.pipeline = pipeline;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running.set(true);

        lastGatewayAccepted =
                pipeline.getGatewayPacketsAccepted();

        trackThread =
                Thread.ofVirtual()
                        .name("kafka-track-bridge")
                        .start(this::consumeTracks);

        eventThread =
                Thread.ofVirtual()
                        .name("kafka-event-bridge")
                        .start(this::consumeEvents);

        log.info(
                "KafkaWebSocketBridge started (bootstrap={})",
                bootstrapServers
        );
    }

    @PreDestroy
    public void stop() {
        running.set(false);

        log.info(
                "KafkaWebSocketBridge stopping. tracks={} events={}",
                tracksConsumed.sum(),
                eventsConsumed.sum()
        );
    }

    private void consumeTracks() {

        Properties props =
                consumerProps("vanguard-api-tracks");

        try (KafkaConsumer<String, byte[]> consumer =
                     new KafkaConsumer<>(props)) {

            consumer.subscribe(List.of(TRACKS_TOPIC));

            log.info(
                    "Track bridge consumer subscribed to {}",
                    TRACKS_TOPIC
            );

            while (running.get()) {

                ConsumerRecords<String, byte[]> records =
                        consumer.poll(Duration.ofMillis(100));

                if (records.isEmpty()) {
                    continue;
                }

                inFlightRecords.addAndGet(records.count());

                for (var record : records) {
                    try {
                        String json =
                                new String(
                                        record.value(),
                                        StandardCharsets.UTF_8
                                );

                        @SuppressWarnings("unchecked")
                        Map<String, Object> track =
                                mapper.readValue(
                                        json,
                                        Map.class
                                );

                        String trackId =
                                String.valueOf(track.get("trackId"));

                        double px =
                                toDouble(track.get("px"));

                        double py =
                                toDouble(track.get("py"));

                        double vx =
                                toDouble(track.get("vx"));

                        double vy =
                                toDouble(track.get("vy"));

                        String state =
                                String.valueOf(track.get("state"));

                        double uncertainty =
                                toDouble(track.get("uncertainty"));

                        long lastUpdateMs =
                                toLong(track.get("lastUpdateMs"));

                        // Live delivery must not depend on Redis.
                        trackHandler.broadcast(json);

                        if ("DROPPED".equals(state)) {
                            trackStates.remove(trackId);
                        } else {
                            trackStates.put(trackId, state);
                        }

                        try {
                            if ("DROPPED".equals(state)) {
                                trackRepo.removeTrack(trackId);
                            } else {
                                trackRepo.updateTrack(
                                        trackId,
                                        px,
                                        py,
                                        vx,
                                        vy,
                                        state,
                                        uncertainty,
                                        lastUpdateMs
                                );
                            }
                        } catch (Exception redisError) {
                            log.warn(
                                    "Redis track persistence failed for {}: {}",
                                    trackId,
                                    redisError.getMessage()
                            );
                        }

                        long latency =
                                Math.max(
                                        0,
                                        System.currentTimeMillis()
                                                - lastUpdateMs
                                );

                        latencySamples.addLast(latency);

                        while (latencySamples.size()
                                > MAX_LATENCY_SAMPLES) {
                            latencySamples.pollFirst();
                        }

                        tracksConsumed.increment();

                    } catch (Exception e) {
                        log.warn(
                                "Failed to process track record: {}",
                                e.getMessage()
                        );
                    } finally {
                        inFlightRecords.decrementAndGet();
                    }
                }

                updateTrackStateCounts();
                updateKafkaLag(consumer, trackKafkaLag);
            }

        } catch (Exception e) {
            if (running.get()) {
                log.error(
                        "Track bridge consumer crashed",
                        e
                );
            }
        }
    }

    private void consumeEvents() {

        Properties props =
                consumerProps("vanguard-api-events");

        try (KafkaConsumer<String, byte[]> consumer =
                     new KafkaConsumer<>(props)) {

            consumer.subscribe(List.of(EVENTS_TOPIC));

            log.info(
                    "Event bridge consumer subscribed to {}",
                    EVENTS_TOPIC
            );

            while (running.get()) {

                ConsumerRecords<String, byte[]> records =
                        consumer.poll(Duration.ofMillis(100));

                if (records.isEmpty()) {
                    continue;
                }

                inFlightRecords.addAndGet(records.count());

                for (var record : records) {
                    try {
                        String json =
                                new String(
                                        record.value(),
                                        StandardCharsets.UTF_8
                                );

                        @SuppressWarnings("unchecked")
                        Map<String, Object> event =
                                mapper.readValue(
                                        json,
                                        Map.class
                                );

                        String eventId =
                                String.valueOf(event.get("eventId"));

                        String trackId =
                                String.valueOf(event.get("trackId"));

                        String zoneId =
                                String.valueOf(event.get("zoneId"));

                        String type =
                                String.valueOf(event.get("type"));

                        long timestampMs =
                                toLong(event.get("timestampMs"));

                        double px =
                                toDouble(event.get("px"));

                        double py =
                                toDouble(event.get("py"));

                        // Live delivery first.
                        eventHandler.broadcast(json);

                        try {
                            eventRepo.storeEvent(
                                    eventId,
                                    trackId,
                                    zoneId,
                                    type,
                                    timestampMs,
                                    px,
                                    py
                            );
                        } catch (Exception redisError) {
                            log.warn(
                                    "Redis event persistence failed for {}: {}",
                                    eventId,
                                    redisError.getMessage()
                            );
                        }

                        eventsConsumed.increment();

                    } catch (Exception e) {
                        log.warn(
                                "Failed to process event record: {}",
                                e.getMessage()
                        );
                    } finally {
                        inFlightRecords.decrementAndGet();
                    }
                }

                updateKafkaLag(consumer, eventKafkaLag);
            }

        } catch (Exception e) {
            if (running.get()) {
                log.error(
                        "Event bridge consumer crashed",
                        e
                );
            }
        }
    }

    @Scheduled(fixedRate = 1500)
    public void broadcastHealth() {

        if (!running.get()) {
            return;
        }

        long now =
                System.currentTimeMillis();

        long elapsed =
                now - windowStartMs;

        if (elapsed >= 2000) {

            long currentAccepted =
                    pipeline.getGatewayPacketsAccepted();

            long delta =
                    Math.max(
                            0,
                            currentAccepted - lastGatewayAccepted
                    );

            throughputPerSec =
                    delta / (elapsed / 1000.0);

            lastGatewayAccepted =
                    currentAccepted;

            windowStartMs = now;
        }

        try {
            List<Long> samples =
                    new ArrayList<>(latencySamples);

            Collections.sort(samples);

            if (!samples.isEmpty()) {
                measuredP50 = percentile(samples, 0.50);
                measuredP95 = percentile(samples, 0.95);
                measuredP99 = percentile(samples, 0.99);
            }

            long totalKafkaLag =
                    trackKafkaLag.get()
                            + eventKafkaLag.get();

            Map<String, Object> health =
                    new LinkedHashMap<>();

            health.put(
                    "throughputReportsPerSec",
                    Math.round(throughputPerSec)
            );

            health.put("p50LatencyMs", measuredP50);
            health.put("p95LatencyMs", measuredP95);
            health.put("p99LatencyMs", measuredP99);

            health.put(
                    "activeTracks",
                    activeTracks.get()
            );

            health.put(
                    "confirmedTracks",
                    confirmedTracks.get()
            );

            health.put(
                    "coastingTracks",
                    coastingTracks.get()
            );

            health.put(
                    "queueDepth",
                    Math.max(
                            0,
                            inFlightRecords.get()
                    )
            );

            health.put(
                    "kafkaLag",
                    totalKafkaLag
            );

            health.put(
                    "packetsDropped",
                    pipeline.getGatewayPacketsDropped()
            );

            health.put(
                    "gatewayPacketsReceived",
                    pipeline.getGatewayPacketsReceived()
            );

            health.put(
                    "gatewayPacketsAccepted",
                    pipeline.getGatewayPacketsAccepted()
            );

            health.put(
                    "trackKafkaLag",
                    trackKafkaLag.get()
            );

            health.put(
                    "eventKafkaLag",
                    eventKafkaLag.get()
            );

            health.put(
                    "uptimeMs",
                    now - startTimeMs
            );

            healthHandler.broadcast(
                    mapper.writeValueAsString(health)
            );

        } catch (Exception e) {
            log.warn(
                    "Failed to broadcast health: {}",
                    e.getMessage()
            );
        }
    }

    private void updateTrackStateCounts() {

        int active = 0;
        int confirmed = 0;
        int coasting = 0;

        for (String state : trackStates.values()) {

            active++;

            if ("CONFIRMED".equals(state)) {
                confirmed++;
            } else if ("COASTING".equals(state)) {
                coasting++;
            }
        }

        activeTracks.set(active);
        confirmedTracks.set(confirmed);
        coastingTracks.set(coasting);
    }

    private void updateKafkaLag(
            KafkaConsumer<String, byte[]> consumer,
            AtomicLong target) {

        try {
            Set<TopicPartition> partitions =
                    consumer.assignment();

            if (partitions.isEmpty()) {
                target.set(0);
                return;
            }

            Map<TopicPartition, Long> endOffsets =
                    consumer.endOffsets(partitions);

            long lag = 0;

            for (TopicPartition partition : partitions) {

                long end =
                        endOffsets.getOrDefault(
                                partition,
                                0L
                        );

                long position =
                        consumer.position(partition);

                lag += Math.max(
                        0,
                        end - position
                );
            }

            target.set(lag);

        } catch (Exception e) {
            log.debug(
                    "Unable to calculate Kafka lag: {}",
                    e.getMessage()
            );
        }
    }

    private static double percentile(
            List<Long> samples,
            double percentile) {

        if (samples.isEmpty()) {
            return 0;
        }

        int index =
                (int) Math.floor(
                        (samples.size() - 1)
                                * percentile
                );

        return samples.get(index);
    }

    private Properties consumerProps(
            String groupId) {

        Properties props =
                new Properties();

        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );

        props.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                groupId
        );

        props.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName()
        );

        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName()
        );

        props.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "latest"
        );

        props.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "true"
        );

        props.put(
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                500
        );

        return props;
    }

    private static double toDouble(Object value) {

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return Double.parseDouble(
                String.valueOf(value)
        );
    }

    private static long toLong(Object value) {

        if (value instanceof Number number) {
            return number.longValue();
        }

        return Long.parseLong(
                String.valueOf(value)
        );
    }
}