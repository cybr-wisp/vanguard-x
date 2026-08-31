package com.vanguard.tracking.pipeline;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Kafka consumer that reads from sensor-reports.raw, runs the tracking
 * pipeline (association + EKF + lifecycle), and publishes fused tracks
 * to tracks.fused.
 *
 * Runs in its own thread. The poll-process-produce loop is the main
 * processing cycle. Consumer group rebalancing provides fault tolerance:
 * if this processor dies, another instance picks up the partitions.
 */
public class TrackingPipelineConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TrackingPipelineConsumer.class);

    private static final String INPUT_TOPIC  = "sensor-reports.raw";
    private static final String OUTPUT_TOPIC = "tracks.fused";

    private final KafkaConsumer<String, byte[]> consumer;
    private final KafkaProducer<String, byte[]> producer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LongAdder consumed = new LongAdder();
    private final LongAdder produced = new LongAdder();

    // The actual tracking logic is injected via this callback
    private final ReportProcessor processor;

    @FunctionalInterface
    public interface ReportProcessor {
        /**
         * Process a batch of raw reports and return fused track records.
         * Each returned record is a key-value pair (trackId -> serialized track).
         */
        List<KeyValue> process(List<ConsumerRecord<String, byte[]>> records);
    }

    public record KeyValue(String key, byte[] value) {}

    public TrackingPipelineConsumer(String bootstrapServers, String groupId,
                                     ReportProcessor processor) {
        this.processor = processor;

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        this.consumer = new KafkaConsumer<>(consumerProps);

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        this.producer = new KafkaProducer<>(producerProps);
    }

    @Override
    public void run() {
        consumer.subscribe(List.of(INPUT_TOPIC));
        log.info("Tracking pipeline consumer started on topic {}", INPUT_TOPIC);

        try {
            while (running.get()) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;

                consumed.add(records.count());

                // Delegate to the tracking processor
                List<KeyValue> output = processor.process(
                        records.records(INPUT_TOPIC).stream().toList());

                // Publish fused tracks
                for (KeyValue kv : output) {
                    producer.send(new ProducerRecord<>(OUTPUT_TOPIC, kv.key(), kv.value()),
                            (metadata, ex) -> {
                                if (ex != null) log.warn("Failed to publish fused track", ex);
                                else produced.increment();
                            });
                }
            }
        } finally {
            consumer.close();
            producer.close();
            log.info("Tracking pipeline consumer stopped. consumed={} produced={}",
                    consumed.sum(), produced.sum());
        }
    }

    public void stop() { running.set(false); }
    public long getConsumed() { return consumed.sum(); }
    public long getProduced() { return produced.sum(); }
}
