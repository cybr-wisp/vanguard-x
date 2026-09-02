package com.vanguard.tracking.lifecycle;

import com.vanguard.tracking.association.DataAssociator;
import com.vanguard.tracking.association.MahalanobisGate;
import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import com.vanguard.tracking.estimation.MotionModel;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuplicateSuppressionTest {

    private static final MotionModel MOTION =
            new MotionModel(1.0);

    private static final MeasurementModel SENSOR =
            new MeasurementModel(
                    0,
                    0,
                    50,
                    0.01
            );

    private static SimpleMatrix measurementAt(
            double px,
            double py) {

        SimpleMatrix state =
                new SimpleMatrix(
                        new double[][]{
                                {px},
                                {py},
                                {0},
                                {0}
                        }
                );

        SimpleMatrix covariance =
                SimpleMatrix.identity(4)
                        .scale(1_000);

        ExtendedKalmanFilter filter =
                new ExtendedKalmanFilter(
                        state,
                        covariance,
                        MOTION
                );

        return SENSOR.h(
                filter.getState()
        );
    }

    @Test
    void nearbyTentativeDuplicateIsSuppressed() {
        TrackManager manager =
                new TrackManager(
                        new DataAssociator(
                                new MahalanobisGate(9.21),
                                1_000.0
                        ),
                        MOTION,
                        3,
                        3,
                        8,
                        10_000,
                        62_500
                );

        SimpleMatrix canonical =
                measurementAt(
                        1_000,
                        500
                );

        manager.processObservations(
                List.of(canonical),
                SENSOR,
                "S1",
                1_000
        );

        manager.processObservations(
                List.of(canonical),
                SENSOR,
                "S1",
                2_000
        );

        manager.processObservations(
                List.of(canonical),
                SENSOR,
                "S1",
                3_000
        );

        assertEquals(
                1,
                manager.getConfirmedCount()
        );

        SimpleMatrix nearby =
                measurementAt(
                        1_180,
                        500
                );

        manager.processObservations(
                List.of(
                        canonical,
                        nearby
                ),
                SENSOR,
                "S1",
                4_000
        );

        assertEquals(
                2,
                manager.getAliveCount(),
                "One-to-one association should leave a nearby extra observation as a tentative hypothesis"
        );

        assertEquals(
                1,
                manager.suppressDuplicateTracks()
        );

        assertEquals(
                1,
                manager.getAliveCount()
        );

        assertEquals(
                1,
                manager.getConfirmedCount()
        );
    }
}
