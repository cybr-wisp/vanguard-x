package com.vanguard;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

final class IntegrationContainers {

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379);

    static {
        KAFKA.start();
        REDIS.start();
    }

    private IntegrationContainers() {
    }

    static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    static String redisUri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    static void ensureTopic(String topic) throws Exception {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaBootstrapServers()
        );

        try (AdminClient admin = AdminClient.create(config)) {
            try {
                admin.createTopics(
                        List.of(new NewTopic(topic, 1, (short) 1))
                ).all().get(15, TimeUnit.SECONDS);

            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw e;
                }
            }
        }
    }

    static Properties producerProperties() {
        Properties props = new Properties();

        props.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaBootstrapServers()
        );

        props.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()
        );

        props.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class.getName()
        );

        props.put(
                ProducerConfig.ACKS_CONFIG,
                "all"
        );

        return props;
    }

    static Properties consumerProperties(
            String groupId,
            String offsetReset
    ) {
        Properties props = new Properties();

        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaBootstrapServers()
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
                offsetReset
        );

        props.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false"
        );

        return props;
    }

    static void awaitConsumerGroup(
            String groupId,
            int expectedMembers,
            Duration timeout
    ) throws Exception {

        long deadline = System.nanoTime() + timeout.toNanos();

        try (
                AdminClient admin = AdminClient.create(
                        Map.of(
                                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                kafkaBootstrapServers()
                        )
                )
        ) {
            while (System.nanoTime() < deadline) {

                try {
                    var description = admin
                            .describeConsumerGroups(List.of(groupId))
                            .describedGroups()
                            .get(groupId)
                            .get(1, TimeUnit.SECONDS);

                    if (description.members().size() >= expectedMembers) {
                        return;
                    }

                } catch (Exception ignored) {
                    // Consumer group has not joined yet.
                }

                Thread.sleep(100);
            }
        }

        throw new IllegalStateException(
                "Consumer group did not become ready: " + groupId
        );
    }

    static void awaitAssignment(
            KafkaConsumer<String, byte[]> consumer,
            Duration timeout
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (
                consumer.assignment().isEmpty()
                        && System.nanoTime() < deadline
        ) {
            consumer.poll(Duration.ofMillis(100));
        }

        if (consumer.assignment().isEmpty()) {
            throw new IllegalStateException(
                    "Kafka consumer did not receive an assignment"
            );
        }
    }

    static List<ConsumerRecord<String, byte[]>> awaitMatchingRecords(
            KafkaConsumer<String, byte[]> consumer,
            Predicate<ConsumerRecord<String, byte[]>> predicate,
            int expected,
            Duration timeout
    ) {

        List<ConsumerRecord<String, byte[]>> matches =
                new ArrayList<>();

        long deadline = System.nanoTime() + timeout.toNanos();

        while (
                matches.size() < expected
                        && System.nanoTime() < deadline
        ) {
            var records =
                    consumer.poll(Duration.ofMillis(250));

            for (ConsumerRecord<String, byte[]> record : records) {
                if (predicate.test(record)) {
                    matches.add(record);
                }
            }
        }

        return matches;
    }
}
