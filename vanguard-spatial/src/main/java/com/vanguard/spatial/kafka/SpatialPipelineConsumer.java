package com.vanguard.spatial.kafka;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.errors.WakeupException;
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
 * Kafka consumer that reads fused tracks from tracks.fused, runs geofence
 * evaluation and the alert state machine, and publishes transition events
 * to track-events.
 *
 * Keyed by trackId+zoneId so per-pair event ordering is preserved.
 */
public class SpatialPipelineConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SpatialPipelineConsumer.class);

    private static final String INPUT_TOPIC  = "tracks.fused";
    private static final String OUTPUT_TOPIC = "track-events";

    private final KafkaConsumer<String, byte[]> consumer;
    private final KafkaProducer<String, byte[]> producer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LongAdder consumed = new LongAdder();
    private final LongAdder eventsPublished = new LongAdder();

    private final TrackEvaluator evaluator;

    @FunctionalInterface
    public interface TrackEvaluator {
        /** Evaluate fused tracks, return any transition events as key-value pairs. */
        List<KeyValue> evaluate(List<ConsumerRecord<String, byte[]>> records);
    }

    public record KeyValue(String key, byte[] value) {}

    public SpatialPipelineConsumer(String bootstrapServers, String groupId,
                                    TrackEvaluator evaluator) {
        this.evaluator = evaluator;

        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        cp.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cp.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        this.consumer = new KafkaConsumer<>(cp);

        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        pp.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        pp.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        pp.put(ProducerConfig.ACKS_CONFIG, "1");
        this.producer = new KafkaProducer<>(pp);
    }

    @Override
    public void run() {
        consumer.subscribe(List.of(INPUT_TOPIC));
        log.info("Spatial pipeline consumer started on topic {}", INPUT_TOPIC);

        try {
            while (running.get()) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;

                consumed.add(records.count());

                List<KeyValue> events = evaluator.evaluate(
                     java.util.stream.StreamSupport.stream(records.records(INPUT_TOPIC).spliterator(), false).toList());
                for (KeyValue kv : events) {
                    producer.send(new ProducerRecord<>(OUTPUT_TOPIC, kv.key(), kv.value()),
                            (metadata, ex) -> {
                                if (ex != null) log.warn("Failed to publish track event", ex);
                                else eventsPublished.increment();
                            });
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        } finally {
            consumer.close();
            producer.close();
            log.info("Spatial pipeline consumer stopped. consumed={} events={}",
                    consumed.sum(), eventsPublished.sum());
        }
    }

    public void stop() {
        running.set(false);
        consumer.wakeup();
    }
    public long getConsumed() { return consumed.sum(); }
    public long getEventsPublished() { return eventsPublished.sum(); }
}
