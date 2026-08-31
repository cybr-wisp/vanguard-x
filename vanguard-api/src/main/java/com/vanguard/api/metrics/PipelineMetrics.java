package com.vanguard.api.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-based instrumentation for the Vanguard pipeline.
 * Every major failure mode has at least one metric that makes it observable.
 *
 * Metric types:
 *   - Counter: monotonic (packets received, dropped, association failures)
 *   - Gauge: current value (active tracks, queue depth, Kafka lag)
 *   - Timer/DistributionSummary: latency percentiles (p50, p95, p99)
 */
@Component
public class PipelineMetrics {

    // --- Gateway ---
    private final Counter packetsReceived;
    private final Counter packetsAccepted;
    private final Counter packetsMalformed;
    private final Counter packetsDuplicate;
    private final Counter packetsDropped;
    private final Timer decodeLatency;

    // --- Tracking ---
    private final Counter associationAttempts;
    private final Counter associationSuccesses;
    private final Counter associationFailures;
    private final Counter tracksCreated;
    private final Counter tracksDropped;
    private final AtomicInteger activeTracks = new AtomicInteger(0);
    private final AtomicInteger confirmedTracks = new AtomicInteger(0);
    private final AtomicInteger coastingTracks = new AtomicInteger(0);
    private final Timer processingLatency;

    // --- Pipeline ---
    private final AtomicInteger queueDepth = new AtomicInteger(0);
    private final AtomicLong kafkaConsumerLag = new AtomicLong(0);
    private final Timer endToEndLatency;

    // --- Spatial ---
    private final Counter zoneEvents;

    public PipelineMetrics(MeterRegistry registry) {
        // Gateway
        packetsReceived  = registry.counter("vanguard.gateway.packets.received");
        packetsAccepted  = registry.counter("vanguard.gateway.packets.accepted");
        packetsMalformed = registry.counter("vanguard.gateway.packets.malformed");
        packetsDuplicate = registry.counter("vanguard.gateway.packets.duplicate");
        packetsDropped   = registry.counter("vanguard.gateway.packets.dropped");
        decodeLatency    = registry.timer("vanguard.gateway.decode.latency");

        // Tracking
        associationAttempts  = registry.counter("vanguard.tracking.association.attempts");
        associationSuccesses = registry.counter("vanguard.tracking.association.successes");
        associationFailures  = registry.counter("vanguard.tracking.association.failures");
        tracksCreated        = registry.counter("vanguard.tracking.tracks.created");
        tracksDropped        = registry.counter("vanguard.tracking.tracks.dropped");
        processingLatency    = registry.timer("vanguard.tracking.processing.latency");

        registry.gauge("vanguard.tracking.tracks.active", activeTracks);
        registry.gauge("vanguard.tracking.tracks.confirmed", confirmedTracks);
        registry.gauge("vanguard.tracking.tracks.coasting", coastingTracks);

        // Pipeline
        registry.gauge("vanguard.pipeline.queue.depth", queueDepth);
        registry.gauge("vanguard.pipeline.kafka.consumer.lag", kafkaConsumerLag);
        endToEndLatency = registry.timer("vanguard.pipeline.e2e.latency");

        // Spatial
        zoneEvents = registry.counter("vanguard.spatial.zone.events");
    }

    // --- Gateway recording ---
    public void recordPacketReceived()  { packetsReceived.increment(); }
    public void recordPacketAccepted()  { packetsAccepted.increment(); }
    public void recordPacketMalformed() { packetsMalformed.increment(); }
    public void recordPacketDuplicate() { packetsDuplicate.increment(); }
    public void recordPacketDropped()   { packetsDropped.increment(); }
    public Timer getDecodeLatency()     { return decodeLatency; }

    // --- Tracking recording ---
    public void recordAssociationAttempt()  { associationAttempts.increment(); }
    public void recordAssociationSuccess()  { associationSuccesses.increment(); }
    public void recordAssociationFailure()  { associationFailures.increment(); }
    public void recordTrackCreated()        { tracksCreated.increment(); }
    public void recordTrackDropped()        { tracksDropped.increment(); }
    public void setActiveTracks(int n)      { activeTracks.set(n); }
    public void setConfirmedTracks(int n)   { confirmedTracks.set(n); }
    public void setCoastingTracks(int n)    { coastingTracks.set(n); }
    public Timer getProcessingLatency()     { return processingLatency; }

    // --- Pipeline recording ---
    public void setQueueDepth(int n)        { queueDepth.set(n); }
    public void setKafkaConsumerLag(long n)  { kafkaConsumerLag.set(n); }
    public Timer getEndToEndLatency()       { return endToEndLatency; }

    // --- Spatial recording ---
    public void recordZoneEvent()           { zoneEvents.increment(); }
}
