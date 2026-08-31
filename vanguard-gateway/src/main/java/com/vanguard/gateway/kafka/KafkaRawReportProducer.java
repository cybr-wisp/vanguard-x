package com.vanguard.gateway.kafka;

import com.vanguard.gateway.PacketValidator.DecodedReport;
import com.vanguard.gateway.RawReportSink;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/**
 * Publishes validated sensor reports to the sensor-reports.raw Kafka topic.
 * Keyed by sensor_id so all reports from one sensor land on the same partition,
 * preserving per-sensor ordering.
 *
 * Implements RawReportSink so the gateway can swap between in-memory (testing)
 * and Kafka (production) without code changes.
 */
public class KafkaRawReportProducer implements RawReportSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaRawReportProducer.class);

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final LongAdder published = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public KafkaRawReportProducer(String bootstrapServers) {
        this(bootstrapServers, KafkaTopicConfig.SENSOR_REPORTS_RAW);
    }

    public KafkaRawReportProducer(String bootstrapServers, String topic) {
        this.topic = topic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");              // leader ack
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);           // batch for throughput
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432L);
        this.producer = new KafkaProducer<>(props);
    }

    @Override
    public boolean offer(DecodedReport report) {
        try {
            // Serialize to a simple delimited format (Protobuf re-serialization in production)
            byte[] value = serializeReport(report);
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(topic, report.sensorId(), value);

            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    failed.increment();
                    log.warn("Failed to publish report from {}: {}",
                            report.sensorId(), exception.getMessage());
                } else {
                    published.increment();
                }
            });
            return true;
        } catch (Exception e) {
            failed.increment();
            log.error("Producer error for {}", report.sensorId(), e);
            return false;
        }
    }

    /**
     * Serialize a decoded report to bytes. In production this would re-encode
     * to Protobuf; here we use a compact CSV for simplicity.
     */
    private byte[] serializeReport(DecodedReport r) {
        String csv = "%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d".formatted(
                r.sensorId(), r.timestampMs(),
                r.sensorX(), r.sensorY(),
                r.range(), r.azimuth(),
                r.signalStrength(), r.sequenceNumber());
        return csv.getBytes();
    }

    public void flush() { producer.flush(); }
    public void close() { producer.close(); }
    public long getPublished() { return published.sum(); }
    public long getFailed() { return failed.sum(); }
}
