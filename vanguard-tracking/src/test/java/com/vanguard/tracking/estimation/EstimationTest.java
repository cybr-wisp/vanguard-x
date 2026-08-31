package com.vanguard.tracking.estimation;

import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EstimationTest {

    // ================================================================
    // MotionModel
    // ================================================================

    @Nested
    @DisplayName("MotionModel")
    class MotionModelTests {

        MotionModel model = new MotionModel(1.0);

        @Test
        @DisplayName("F(dt) advances position by velocity * dt")
        void transitionMatrixAdvancesPosition() {
            SimpleMatrix x = new SimpleMatrix(new double[][]{{100}, {200}, {10}, {-5}});
            SimpleMatrix xPred = model.predict(x, 3.0);

            assertEquals(130.0, xPred.get(0), 1e-9, "px = 100 + 10*3");
            assertEquals(185.0, xPred.get(1), 1e-9, "py = 200 + (-5)*3");
            assertEquals(10.0,  xPred.get(2), 1e-9, "vx unchanged");
            assertEquals(-5.0,  xPred.get(3), 1e-9, "vy unchanged");
        }

        @Test
        @DisplayName("F(0) is identity")
        void zeroTimestepIsIdentity() {
            SimpleMatrix F = model.transitionMatrix(0);
            SimpleMatrix I = SimpleMatrix.identity(4);
            assertTrue(F.isIdentical(I, 1e-15));
        }

        @Test
        @DisplayName("Q(dt) is symmetric positive semidefinite")
        void processNoiseSymmetric() {
            SimpleMatrix Q = model.processNoise(0.1);
            // Symmetric
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++)
                    assertEquals(Q.get(i, j), Q.get(j, i), 1e-15,
                            "Q[%d,%d] != Q[%d,%d]".formatted(i, j, j, i));
            // Diagonal non-negative
            for (int i = 0; i < 4; i++)
                assertTrue(Q.get(i, i) >= 0, "Q[%d,%d] should be >= 0".formatted(i, i));
        }

        @Test
        @DisplayName("Q grows with dt (longer prediction = more uncertainty)")
        void processNoiseGrowsWithDt() {
            double traceShort = model.processNoise(0.1).trace();
            double traceLong  = model.processNoise(1.0).trace();
            assertTrue(traceLong > traceShort, "Longer dt should produce larger Q");
        }

        @Test
        @DisplayName("Covariance grows during prediction (no observations)")
        void covarianceGrowsDuringPrediction() {
            SimpleMatrix x = new SimpleMatrix(new double[][]{{0}, {0}, {10}, {5}});
            SimpleMatrix P = SimpleMatrix.identity(4).scale(100);
            double traceBefore = P.trace();

            SimpleMatrix[] result = model.predictWithCovariance(x, P, 1.0);
            double traceAfter = result[1].trace();

            assertTrue(traceAfter > traceBefore,
                    "Covariance should grow without observations");
        }
    }

    // ================================================================
    // MeasurementModel
    // ================================================================

    @Nested
    @DisplayName("MeasurementModel")
    class MeasurementModelTests {

        @Test
        @DisplayName("h(x) computes correct range and bearing")
        void hComputesRangeBearing() {
            // Sensor at origin, target at (300, 400)
            MeasurementModel mm = new MeasurementModel(0, 0, 50, 0.01);
            SimpleMatrix x = new SimpleMatrix(new double[][]{{300}, {400}, {0}, {0}});
            SimpleMatrix z = mm.h(x);

            assertEquals(500.0, z.get(0), 1e-9, "range = sqrt(300^2+400^2)");
            assertEquals(Math.atan2(400, 300), z.get(1), 1e-9, "bearing = atan2(400,300)");
        }

        @Test
        @DisplayName("h(x) accounts for sensor offset")
        void hAccountsForSensorOffset() {
            // Sensor at (100, 100), target at (400, 500) -> displacement (300, 400)
            MeasurementModel mm = new MeasurementModel(100, 100, 50, 0.01);
            SimpleMatrix x = new SimpleMatrix(new double[][]{{400}, {500}, {0}, {0}});
            SimpleMatrix z = mm.h(x);

            assertEquals(500.0, z.get(0), 1e-9);
            assertEquals(Math.atan2(400, 300), z.get(1), 1e-9);
        }

        @Test
        @DisplayName("Jacobian matches numerical differentiation")
        void jacobianMatchesNumerical() {
            MeasurementModel mm = new MeasurementModel(50, -30, 50, 0.01);
            SimpleMatrix x = new SimpleMatrix(new double[][]{{500}, {300}, {10}, {-5}});

            SimpleMatrix H = mm.jacobian(x);

            // Numerical Jacobian by finite differences
            double eps = 1e-6;
            SimpleMatrix Hnum = new SimpleMatrix(2, 4);
            for (int col = 0; col < 4; col++) {
                SimpleMatrix xp = x.copy();
                SimpleMatrix xm = x.copy();
                xp.set(col, 0, xp.get(col, 0) + eps);
                xm.set(col, 0, xm.get(col, 0) - eps);
                SimpleMatrix diff = mm.h(xp).minus(mm.h(xm)).scale(1.0 / (2 * eps));
                for (int row = 0; row < 2; row++) {
                    Hnum.set(row, col, diff.get(row, 0));
                }
            }

            for (int i = 0; i < 2; i++)
                for (int j = 0; j < 4; j++)
                    assertEquals(Hnum.get(i, j), H.get(i, j), 1e-5,
                            "H[%d,%d] mismatch".formatted(i, j));
        }

        @Test
        @DisplayName("Jacobian velocity columns are zero (velocity not observed)")
        void jacobianVelocityColumnsZero() {
            MeasurementModel mm = new MeasurementModel(0, 0, 50, 0.01);
            SimpleMatrix x = new SimpleMatrix(new double[][]{{300}, {400}, {10}, {-5}});
            SimpleMatrix H = mm.jacobian(x);

            assertEquals(0, H.get(0, 2), 1e-15, "H[0,2] should be 0");
            assertEquals(0, H.get(0, 3), 1e-15, "H[0,3] should be 0");
            assertEquals(0, H.get(1, 2), 1e-15, "H[1,2] should be 0");
            assertEquals(0, H.get(1, 3), 1e-15, "H[1,3] should be 0");
        }

        @Test
        @DisplayName("R is diagonal with squared sigma values")
        void noiseCovariance() {
            MeasurementModel mm = new MeasurementModel(0, 0, 50, 0.02);
            SimpleMatrix R = mm.noiseCovariance();
            assertEquals(2500.0, R.get(0, 0), 1e-9, "R[0,0] = 50^2");
            assertEquals(0.0004, R.get(1, 1), 1e-9, "R[1,1] = 0.02^2");
            assertEquals(0,      R.get(0, 1), 1e-15, "R off-diagonal = 0");
        }

        @Test
        @DisplayName("Bearing normalization to [-pi, pi]")
        void bearingNorm() {
            assertEquals(0, MeasurementModel.normalizeBearing(0), 1e-12);
            assertEquals(0, MeasurementModel.normalizeBearing(2 * Math.PI), 1e-12);
            assertEquals(0, MeasurementModel.normalizeBearing(-2 * Math.PI), 1e-12);
            assertEquals(1.0, MeasurementModel.normalizeBearing(1.0 + 4 * Math.PI), 1e-12);
            assertEquals(-1.0, MeasurementModel.normalizeBearing(-1.0 - 4 * Math.PI), 1e-12);
            // Near boundary
            double val = MeasurementModel.normalizeBearing(Math.PI + 0.1);
            assertTrue(val < 0, "PI+0.1 should wrap to negative");
        }
    }

    // ================================================================
    // ExtendedKalmanFilter
    // ================================================================

    @Nested
    @DisplayName("ExtendedKalmanFilter")
    class EKFTests {

        @Test
        @DisplayName("EKF converges: estimate improves with observations")
        void convergence() {
            // Scenario: target at (1000, 500) moving at (10, 5) m/s.
            // Sensor at origin. Observe every 1 second for 20 seconds.
            // The estimate should converge to truth and uncertainty should shrink.
            double truePx0 = 1000, truePy0 = 500, trueVx = 10, trueVy = 5;
            double sensorX = 0, sensorY = 0;
            double sigmaRange = 50, sigmaBearing = 0.01;

            MotionModel motion = new MotionModel(1.0);
            MeasurementModel sensor = new MeasurementModel(sensorX, sensorY, sigmaRange, sigmaBearing);

            // Initialize with rough guess (100m off in each axis, wrong velocity)
            SimpleMatrix x0 = new SimpleMatrix(new double[][]{{900}, {400}, {0}, {0}});
            SimpleMatrix P0 = SimpleMatrix.identity(4).scale(10000); // large initial uncertainty

            ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, motion);

            double initialError = Math.sqrt(
                    Math.pow(ekf.getPx() - truePx0, 2) + Math.pow(ekf.getPy() - truePy0, 2));

            java.util.Random rng = new java.util.Random(42);
            double dt = 1.0;

            for (int k = 1; k <= 20; k++) {
                // True position at time k
                double truePx = truePx0 + trueVx * k * dt;
                double truePy = truePy0 + trueVy * k * dt;

                // Predict
                ekf.predict(dt);

                // Generate noisy measurement
                double dx = truePx - sensorX;
                double dy = truePy - sensorY;
                double trueRange   = Math.sqrt(dx * dx + dy * dy);
                double trueBearing = Math.atan2(dy, dx);
                double noisyRange   = trueRange + rng.nextGaussian() * sigmaRange;
                double noisyBearing = trueBearing + rng.nextGaussian() * sigmaBearing;

                SimpleMatrix z = new SimpleMatrix(new double[][]{{noisyRange}, {noisyBearing}});
                ekf.update(z, sensor);
            }

            double finalPx = truePx0 + trueVx * 20;
            double finalPy = truePy0 + trueVy * 20;
            double finalError = Math.sqrt(
                    Math.pow(ekf.getPx() - finalPx, 2) + Math.pow(ekf.getPy() - finalPy, 2));

            assertTrue(finalError < initialError,
                    "Final error (%.1f) should be less than initial (%.1f)".formatted(finalError, initialError));
            assertTrue(finalError < 100,
                    "Final error (%.1f) should be within 100m after 20 observations".formatted(finalError));
        }

        @Test
        @DisplayName("EKF reduces position error vs raw noisy observations")
        void filterBetterThanRaw() {
            // Single target, single sensor, 50 updates.
            // Compare EKF position error to raw observation error.
            double truePx0 = 2000, truePy0 = 1000, trueVx = 15, trueVy = -8;
            double sx = 0, sy = 0;
            double sigmaR = 60, sigmaB = 0.015;

            MotionModel motion = new MotionModel(2.0);
            MeasurementModel sensor = new MeasurementModel(sx, sy, sigmaR, sigmaB);

            SimpleMatrix x0 = new SimpleMatrix(new double[][]{{truePx0}, {truePy0}, {10}, {0}});
            SimpleMatrix P0 = SimpleMatrix.identity(4).scale(5000);
            ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, motion);

            java.util.Random rng = new java.util.Random(99);
            double dt = 0.5;
            double sumEkfError2 = 0, sumRawError2 = 0;
            int count = 0;

            for (int k = 1; k <= 50; k++) {
                double truePx = truePx0 + trueVx * k * dt;
                double truePy = truePy0 + trueVy * k * dt;

                ekf.predict(dt);

                double dx = truePx - sx;
                double dy = truePy - sy;
                double trueRange   = Math.sqrt(dx * dx + dy * dy);
                double trueBearing = Math.atan2(dy, dx);
                double noisyRange   = trueRange + rng.nextGaussian() * sigmaR;
                double noisyBearing = trueBearing + rng.nextGaussian() * sigmaB;

                // Raw observation converted to Cartesian for error comparison
                double rawPx = sx + noisyRange * Math.cos(noisyBearing);
                double rawPy = sy + noisyRange * Math.sin(noisyBearing);
                sumRawError2 += Math.pow(rawPx - truePx, 2) + Math.pow(rawPy - truePy, 2);

                SimpleMatrix z = new SimpleMatrix(new double[][]{{noisyRange}, {noisyBearing}});
                ekf.update(z, sensor);

                sumEkfError2 += Math.pow(ekf.getPx() - truePx, 2) + Math.pow(ekf.getPy() - truePy, 2);
                count++;
            }

            double ekfRmse = Math.sqrt(sumEkfError2 / count);
            double rawRmse = Math.sqrt(sumRawError2 / count);

            assertTrue(ekfRmse < rawRmse,
                    "EKF RMSE (%.1f) should be less than raw (%.1f)".formatted(ekfRmse, rawRmse));
        }

        @Test
        @DisplayName("Covariance grows during coasting (no observations)")
        void covarianceGrowsDuringCoasting() {
            MotionModel motion = new MotionModel(2.0);
            SimpleMatrix x0 = new SimpleMatrix(new double[][]{{100}, {200}, {10}, {5}});
            SimpleMatrix P0 = SimpleMatrix.identity(4).scale(100);
            ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, motion);

            double uncBefore = ekf.getPositionUncertainty();
            for (int i = 0; i < 10; i++) {
                ekf.predict(1.0);
            }
            double uncAfter = ekf.getPositionUncertainty();

            assertTrue(uncAfter > uncBefore,
                    "Uncertainty should grow during coasting: %.1f -> %.1f".formatted(uncBefore, uncAfter));
        }

        @Test
        @DisplayName("Update reduces uncertainty")
        void updateReducesUncertainty() {
            MotionModel motion = new MotionModel(1.0);
            MeasurementModel sensor = new MeasurementModel(0, 0, 50, 0.01);

            SimpleMatrix x0 = new SimpleMatrix(new double[][]{{500}, {500}, {10}, {5}});
            SimpleMatrix P0 = SimpleMatrix.identity(4).scale(10000);
            ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, motion);

            double uncBefore = ekf.getPositionUncertainty();

            // Perfect measurement at (500, 500) -> range ~707, bearing ~0.785
            SimpleMatrix z = sensor.h(x0); // noiseless for this test
            ekf.update(z, sensor);

            double uncAfter = ekf.getPositionUncertainty();
            assertTrue(uncAfter < uncBefore,
                    "Update should reduce uncertainty: %.1f -> %.1f".formatted(uncBefore, uncAfter));
        }

        @Test
        @DisplayName("Innovation computed without modifying filter state")
        void innovationDoesNotModifyState() {
            MotionModel motion = new MotionModel(1.0);
            MeasurementModel sensor = new MeasurementModel(0, 0, 50, 0.01);

            SimpleMatrix x0 = new SimpleMatrix(new double[][]{{300}, {400}, {10}, {5}});
            SimpleMatrix P0 = SimpleMatrix.identity(4).scale(1000);
            ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, motion);

            SimpleMatrix stateBefore = ekf.getState();
            SimpleMatrix z = new SimpleMatrix(new double[][]{{500}, {0.93}});
            ekf.computeInnovation(z, sensor);
            SimpleMatrix stateAfter = ekf.getState();

            assertTrue(stateBefore.isIdentical(stateAfter, 1e-15),
                    "computeInnovation should not modify state");
        }

        @Test
        @DisplayName("Bearing wrapping near pi boundary handled correctly")
        void bearingWrappingNearPi() {
            // Target nearly behind the sensor. Bearing ~ pi.
            // Noisy measurement crosses the -pi/pi boundary.
            MotionModel motion = new MotionModel(1.0);
            MeasurementModel sensor = new MeasurementModel(0, 0, 50, 0.02);

            SimpleMatrix x0 = new SimpleMatrix(new double[][]{{-1000}, {1}, {-10}, {0}});
            SimpleMatrix P0 = SimpleMatrix.identity(4).scale(5000);
            ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, motion);

            // True bearing is very close to pi. Noisy measurement wraps to -pi + small
            double trueRange = 1000.0;
            double noisyBearing = -Math.PI + 0.01; // just past the boundary

            SimpleMatrix z = new SimpleMatrix(new double[][]{{trueRange}, {noisyBearing}});

            // This should NOT cause a huge innovation or NaN
            assertDoesNotThrow(() -> ekf.update(z, sensor));
            assertFalse(Double.isNaN(ekf.getPx()), "State should not be NaN after pi-boundary update");
        }
    }
}
