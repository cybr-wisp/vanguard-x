package com.vanguard;

import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import com.vanguard.tracking.estimation.MotionModel;
import com.vanguard.tracking.lifecycle.Track;
import com.vanguard.tracking.lifecycle.TrackState;
import io.lettuce.core.RedisClient;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketLossIT {

    @Test
    void confirmedTrackCoastsAndReacquiresWithoutIdentityChange() {

        long t0 = 1_000_000L;

        MotionModel motionModel =
                new MotionModel(2.0);

        SimpleMatrix initialState =
                new SimpleMatrix(
                        new double[][]{
                                {1000.0},
                                {500.0},
                                {30.0},
                                {-5.0}
                        }
                );

        SimpleMatrix initialCovariance =
                SimpleMatrix.identity(4)
                        .scale(100.0);

        ExtendedKalmanFilter ekf =
                new ExtendedKalmanFilter(
                        initialState,
                        initialCovariance,
                        motionModel
                );

        MeasurementModel sensor =
                new MeasurementModel(
                        0.0,
                        0.0,
                        10.0,
                        Math.toRadians(0.5)
                );

        Track track =
                new Track(
                        "TRK-PACKET-LOSS",
                        ekf,
                        motionModel,
                        t0,
                        3,
                        3,
                        8
                );

        String canonicalTrackId =
                track.getTrackId();

        /*
         * Creation counts as hit #1.
         * Two subsequent observation cycles confirm the track.
         */
        track.update(
                measurementFor(
                        sensor,
                        1030.0,
                        495.0,
                        30.0,
                        -5.0
                ),
                sensor,
                "SENSOR-A",
                t0 + 1_000
        );

        track.update(
                measurementFor(
                        sensor,
                        1060.0,
                        490.0,
                        30.0,
                        -5.0
                ),
                sensor,
                "SENSOR-A",
                t0 + 2_000
        );

        assertEquals(
                TrackState.CONFIRMED,
                track.getState(),
                "track should be confirmed before packet loss"
        );

        double uncertaintyBeforeLoss =
                track.getPositionUncertainty();

        /*
         * Simulate three consecutive missed detection cycles.
         * missesToCoast = 3.
         */
        track.recordMiss(t0 + 3_000);
        track.recordMiss(t0 + 4_000);
        track.recordMiss(t0 + 5_000);

        assertEquals(
                TrackState.COASTING,
                track.getState(),
                "three missed cycles should move a confirmed track to COASTING"
        );

        double uncertaintyAfterLoss =
                track.getPositionUncertainty();

        assertTrue(
                uncertaintyAfterLoss > uncertaintyBeforeLoss,
                "uncertainty should grow while the track is coasting"
        );

        /*
         * A valid observation after the outage should reacquire the
         * same canonical track rather than creating a new identity.
         */
        track.update(
                measurementFor(
                        sensor,
                        1180.0,
                        470.0,
                        30.0,
                        -5.0
                ),
                sensor,
                "SENSOR-A",
                t0 + 6_000
        );

        assertEquals(
                TrackState.CONFIRMED,
                track.getState(),
                "reacquisition should return the track to CONFIRMED"
        );

        assertEquals(
                canonicalTrackId,
                track.getTrackId(),
                "reacquisition must preserve the canonical track identity"
        );

        assertTrue(
                track.getContributingSensors().contains("SENSOR-A"),
                "sensor provenance should survive reacquisition"
        );

        /*
         * Persist the recovered state through a real Redis container.
         */
        String redisKey =
                "packet-loss-it:" + UUID.randomUUID();

        String redisValue =
                track.getTrackId()
                        + "|"
                        + track.getState().name();

        RedisClient redis =
                RedisClient.create(
                        IntegrationContainers.redisUri()
                );

        try {
            var connection = redis.connect();

            try {
                connection
                        .sync()
                        .set(redisKey, redisValue);

                assertEquals(
                        redisValue,
                        connection.sync().get(redisKey),
                        "recovered track state should round-trip through Redis"
                );
            } finally {
                connection.close();
            }
        } finally {
            redis.shutdown();
        }
    }

    private static SimpleMatrix measurementFor(
            MeasurementModel sensor,
            double px,
            double py,
            double vx,
            double vy
    ) {
        SimpleMatrix truthState =
                new SimpleMatrix(
                        new double[][]{
                                {px},
                                {py},
                                {vx},
                                {vy}
                        }
                );

        return sensor.h(truthState);
    }
}
