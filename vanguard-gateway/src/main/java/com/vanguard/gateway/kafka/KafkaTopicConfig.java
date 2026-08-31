package com.vanguard.gateway.kafka;

/**
 * Central topic configuration. All Kafka topic names, partition counts,
 * and keying strategies are defined here for consistency across modules.
 *
 * Topic design decisions (ADR-002):
 *   - sensor-reports.raw: keyed by sensor_id for per-sensor ordering
 *   - tracks.fused: keyed by track_id for per-track ordering
 *   - track-events: keyed by track_id + zone_id for per-pair ordering
 *   - system-events: keyed by component_id for per-component ordering
 */
public final class KafkaTopicConfig {

    private KafkaTopicConfig() {}

    // Topic names
    public static final String SENSOR_REPORTS_RAW = "sensor-reports.raw";
    public static final String TRACKS_FUSED       = "tracks.fused";
    public static final String TRACK_EVENTS       = "track-events";
    public static final String SYSTEM_EVENTS      = "system-events";

    // Partition counts (configurable via properties in production)
    public static final int SENSOR_REPORTS_PARTITIONS = 3;  // one per sensor
    public static final int TRACKS_FUSED_PARTITIONS   = 6;
    public static final int TRACK_EVENTS_PARTITIONS   = 3;
    public static final int SYSTEM_EVENTS_PARTITIONS  = 1;

    // Replication (1 for local dev, 3 for production)
    public static final short REPLICATION_FACTOR = 1;
}
