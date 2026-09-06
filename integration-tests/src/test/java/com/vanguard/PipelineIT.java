package com.vanguard;

import com.vanguard.tracking.pipeline.TrackingPipelineConsumer;
import io.lettuce.core.RedisClient;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineIT {

    @Test
    void rawReportsFlowThroughTrackingKafkaAdapterAndRedis()
            throws Exception {

        IntegrationContainers.ensureTopic("sensor-reports.raw");
        IntegrationContainers.ensureTopic("tracks.fused");

        String token = UUID.randomUUID().toString();
        String trackingGroup = "pipeline-it-tracking-" + token;

        TrackingPipelineConsumer tracking =
                new TrackingPipelineConsumer(
                        IntegrationContainers.kafkaBootstrapServers(),
                        trackingGroup,
                        records -> records.stream()
                                .map(record ->
                                        new TrackingPipelineConsumer.KeyValue(
                                                "fused-" + record.key(),
                                                (
                                                        "fused:"
                                                                + new String(
                                                                record.value(),
                                                                StandardCharsets.UTF_8
                                                        )
                                                ).getBytes(
                                                        StandardCharsets.UTF_8
                                                )
                                        )
                                )
                                .toList()
                );

        Thread trackingThread = Thread
                .ofVirtual()
                .name("pipeline-it-tracking")
                .start(tracking);

        try {
            IntegrationContainers.awaitConsumerGroup(
                    trackingGroup,
                    1,
                    Duration.ofSeconds(15)
            );

            try (
                    KafkaConsumer<String, byte[]> outputConsumer =
                            new KafkaConsumer<>(
                                    IntegrationContainers.consumerProperties(
                                            "pipeline-it-output-" + token,
                                            "latest"
                                    )
                            )
            ) {
                outputConsumer.subscribe(List.of("tracks.fused"));

                IntegrationContainers.awaitAssignment(
                        outputConsumer,
                        Duration.ofSeconds(10)
                );

                try (
                        KafkaProducer<String, byte[]> producer =
                                new KafkaProducer<>(
                                        IntegrationContainers
                                                .producerProperties()
                                )
                ) {
                    for (int i = 0; i < 3; i++) {
                        producer.send(
                                new ProducerRecord<>(
                                        "sensor-reports.raw",
                                        token + "-" + i,
                                        ("report-" + i).getBytes(
                                                StandardCharsets.UTF_8
                                        )
                                )
                        );
                    }

                    producer.flush();
                }

                var outputs =
                        IntegrationContainers.awaitMatchingRecords(
                                outputConsumer,
                                record ->
                                        record.key() != null
                                                && record.key().startsWith(
                                                "fused-" + token
                                        ),
                                3,
                                Duration.ofSeconds(15)
                        );

                assertEquals(
                        3,
                        outputs.size(),
                        "all three test reports should reach tracks.fused"
                );

                assertTrue(
                        tracking.getConsumed() >= 3,
                        "tracking adapter should consume the raw reports"
                );

                assertTrue(
                        tracking.getProduced() >= 3,
                        "tracking adapter should publish fused records"
                );

                String redisKey = "pipeline-it:" + token;
                String redisValue = outputs.get(0).key();

                RedisClient redis =
                        RedisClient.create(
                                IntegrationContainers.redisUri()
                        );

                try {
                    var connection = redis.connect();

                    try {
                        connection.sync().set(
                                redisKey,
                                redisValue
                        );

                        assertEquals(
                                redisValue,
                                connection.sync().get(redisKey),
                                "Redis should persist pipeline state"
                        );
                    } finally {
                        connection.close();
                    }
                } finally {
                    redis.shutdown();
                }
            }

        } finally {
            tracking.stop();
            trackingThread.join(5_000);

            assertFalse(
                    trackingThread.isAlive(),
                    "tracking consumer should shut down cleanly"
            );
        }
    }
}
