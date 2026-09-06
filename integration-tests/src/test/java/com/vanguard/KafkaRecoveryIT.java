package com.vanguard;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaRecoveryIT {

    @Test
    void restartedConsumerResumesFromCommittedOffset()
            throws Exception {

        String token = UUID.randomUUID().toString();
        String topic = "kafka-recovery-" + token;
        String groupId = "kafka-recovery-group-" + token;

        IntegrationContainers.ensureTopic(topic);

        try (
                KafkaProducer<String, byte[]> producer =
                        new KafkaProducer<>(
                                IntegrationContainers.producerProperties()
                        )
        ) {
            for (int i = 0; i < 20; i++) {
                producer.send(
                        new ProducerRecord<>(
                                topic,
                                "k-" + i,
                                ("value-" + i).getBytes(
                                        StandardCharsets.UTF_8
                                )
                        )
                );
            }

            producer.flush();
        }

        Set<String> firstConsumerKeys = new HashSet<>();

        var firstProps =
                IntegrationContainers.consumerProperties(
                        groupId,
                        "earliest"
                );

        firstProps.put(
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                "5"
        );

        try (
                KafkaConsumer<String, byte[]> firstConsumer =
                        new KafkaConsumer<>(firstProps)
        ) {
            firstConsumer.subscribe(List.of(topic));

            /*
             * Do not pre-poll only to wait for assignment here.
             * poll() may both establish the assignment and return records.
             * Those records must be counted rather than discarded.
             */
            long deadline =
                    System.nanoTime()
                            + Duration.ofSeconds(10).toNanos();

            while (
                    firstConsumerKeys.size() < 5
                            && System.nanoTime() < deadline
            ) {
                var records =
                        firstConsumer.poll(
                                Duration.ofMillis(250)
                        );

                records.forEach(
                        record ->
                                firstConsumerKeys.add(
                                        record.key()
                                )
                );
            }

            assertEquals(
                    5,
                    firstConsumerKeys.size(),
                    "first consumer should process exactly five records"
            );

            firstConsumer.commitSync();
        }

        List<String> recoveredKeys = new ArrayList<>();

        var restartedProps =
                IntegrationContainers.consumerProperties(
                        groupId,
                        "earliest"
                );

        try (
                KafkaConsumer<String, byte[]> restartedConsumer =
                        new KafkaConsumer<>(restartedProps)
        ) {
            restartedConsumer.subscribe(List.of(topic));

            /*
             * The first poll after subscribe may already contain the
             * records beginning at the committed offset.
             */
            long deadline =
                    System.nanoTime()
                            + Duration.ofSeconds(15).toNanos();

            while (
                    recoveredKeys.size() < 15
                            && System.nanoTime() < deadline
            ) {
                var records =
                        restartedConsumer.poll(
                                Duration.ofMillis(250)
                        );

                records.forEach(
                        record ->
                                recoveredKeys.add(
                                        record.key()
                                )
                );
            }
        }

        assertEquals(
                15,
                recoveredKeys.size(),
                "restarted consumer should receive the 15 uncommitted records"
        );

        for (int i = 0; i < 5; i++) {
            assertFalse(
                    recoveredKeys.contains("k-" + i),
                    "committed record must not be replayed: k-" + i
            );
        }

        for (int i = 5; i < 20; i++) {
            assertTrue(
                    recoveredKeys.contains("k-" + i),
                    "unprocessed record should be recovered: k-" + i
            );
        }
    }
}
