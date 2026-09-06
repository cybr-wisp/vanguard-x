package com.vanguard.tracking.association;

import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MahalanobisGateTest {

    @Test
    void closedFormDistanceMatchesMatrixDefinition() {

        MahalanobisGate gate =
                new MahalanobisGate(9.21);

        Random random =
                new Random(20260906L);

        for (int i = 0; i < 1000; i++) {

            double y0 =
                    -100.0 +
                            200.0 * random.nextDouble();

            double y1 =
                    -0.5 +
                            random.nextDouble();

            /*
             * Construct a symmetric positive-definite 2x2 covariance.
             */
            double a =
                    1.0 +
                            1000.0 * random.nextDouble();

            double c =
                    0.0001 +
                            0.05 * random.nextDouble();

            double maxCross =
                    0.8 *
                            Math.sqrt(a * c);

            double b =
                    (2.0 * random.nextDouble() - 1.0) *
                            maxCross;

            SimpleMatrix innovation =
                    new SimpleMatrix(
                            new double[][]{
                                    {y0},
                                    {y1}
                            }
                    );

            SimpleMatrix covariance =
                    new SimpleMatrix(
                            new double[][]{
                                    {a, b},
                                    {b, c}
                            }
                    );

            double expected =
                    innovation.transpose()
                            .mult(covariance.invert())
                            .mult(innovation)
                            .get(0, 0);

            double actual =
                    gate.squaredDistance(
                            innovation,
                            covariance
                    );

            assertEquals(
                    expected,
                    actual,
                    Math.max(
                            1e-10,
                            Math.abs(expected) * 1e-10
                    )
            );
        }
    }
}
