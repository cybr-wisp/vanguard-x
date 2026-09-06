package com.vanguard;

import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import com.vanguard.tracking.estimation.MotionModel;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayIT {

    private static final long REPLAY_SEED = 20260906L;

    @Test
    void deterministicEstimatorReplayProducesIdenticalStateAndKafkaPayload()
            throws Exception {

        String firstReplay = runReplay(REPLAY_SEED);
        String secondReplay = runReplay(REPLAY_SEED);

        assertEquals(
                firstReplay,
                secondReplay,
                "same seed and measurements must produce an identical estimator replay"
        );

        String token = UUID.randomUUID().toString();
        String topic = "replay-it-" + token;

        IntegrationContainers.ensureTopic(topic);

        try (
                KafkaProducer<String, byte[]> producer =
                        new KafkaProducer<>(
                                IntegrationContainers.producerProperties()
                        )
        ) {
            producer.send(
                    new ProducerRecord<>(
                            topic,
                            "replay-1",
                            firstReplay.getBytes(StandardCharsets.UTF_8)
                    )
            );

            producer.send(
                    new ProducerRecord<>(
                            topic,
                            "replay-2",
                            secondReplay.getBytes(StandardCharsets.UTF_8)
                    )
            );

            producer.flush();
        }

        List<String> payloads = new ArrayList<>();

        try (
                KafkaConsumer<String, byte[]> consumer =
                        new KafkaConsumer<>(
                                IntegrationContainers.consumerProperties(
                                        "replay-it-group-" + token,
                                        "earliest"
                                )
                        )
        ) {
            consumer.subscribe(List.of(topic));

            long deadline =
                    System.nanoTime()
                            + Duration.ofSeconds(15).toNanos();

            while (
                    payloads.size() < 2
                            && System.nanoTime() < deadline
            ) {
                var records =
                        consumer.poll(
                                Duration.ofMillis(250)
                        );

                records.forEach(
                        record ->
                                payloads.add(
                                        new String(
                                                record.value(),
                                                StandardCharsets.UTF_8
                                        )
                                )
                );
            }
        }

        assertEquals(
                2,
                payloads.size(),
                "both replay fingerprints should round-trip through Kafka"
        );

        assertEquals(
                firstReplay,
                payloads.get(0),
                "first Kafka payload should preserve the replay fingerprint"
        );

        assertEquals(
                firstReplay,
                payloads.get(1),
                "second Kafka payload should match the deterministic replay"
        );
    }

    private static String runReplay(long seed) {

        MotionModel motionModel =
                new MotionModel(1.5);

        MeasurementModel sensor =
                new MeasurementModel(
                        0.0,
                        0.0,
                        8.0,
                        0.003
                );

        SimpleMatrix initialState =
                new SimpleMatrix(
                        new double[][]{
                                {100.0},
                                {200.0},
                                {15.0},
                                {-4.0}
                        }
                );

        ExtendedKalmanFilter filter =
                new ExtendedKalmanFilter(
                        initialState,
                        SimpleMatrix.identity(4).scale(250.0),
                        motionModel
                );

        Random random =
                new Random(seed);

        double truePx = 100.0;
        double truePy = 200.0;
        double trueVx = 15.0;
        double trueVy = -4.0;
        double dt = 0.1;

        for (int step = 0; step < 120; step++) {

            truePx += trueVx * dt;
            truePy += trueVy * dt;

            filter.predict(dt);

            SimpleMatrix truthState =
                    new SimpleMatrix(
                            new double[][]{
                                    {truePx},
                                    {truePy},
                                    {trueVx},
                                    {trueVy}
                            }
                    );

            SimpleMatrix measurement =
                    sensor.h(truthState);

            measurement.set(
                    0,
                    0,
                    measurement.get(0, 0)
                            + random.nextGaussian() * 8.0
            );

            measurement.set(
                    1,
                    0,
                    measurement.get(1, 0)
                            + random.nextGaussian() * 0.003
            );

            filter.update(
                    measurement,
                    sensor
            );
        }

        return fingerprint(
                filter.getState(),
                filter.getCovariance()
        );
    }

    private static String fingerprint(
            SimpleMatrix state,
            SimpleMatrix covariance
    ) {
        StringBuilder result =
                new StringBuilder();

        for (int row = 0; row < state.numRows(); row++) {
            appendBits(
                    result,
                    state.get(row, 0)
            );
        }

        for (int row = 0; row < covariance.numRows(); row++) {
            for (
                    int column = 0;
                    column < covariance.numCols();
                    column++
            ) {
                appendBits(
                        result,
                        covariance.get(row, column)
                );
            }
        }

        return result.toString();
    }

    private static void appendBits(
            StringBuilder result,
            double value
    ) {
        if (!result.isEmpty()) {
            result.append(':');
        }

        result.append(
                Long.toHexString(
                        Double.doubleToLongBits(value)
                )
        );
    }
}
