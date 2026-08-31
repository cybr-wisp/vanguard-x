package com.vanguard.tracking.association;

import org.ejml.simple.SimpleMatrix;

/**
 * Mahalanobis gating: decides whether a measurement plausibly belongs to a
 * predicted track by comparing the squared Mahalanobis distance against a
 * threshold derived from the chi-square distribution.
 *
 * d^2 = y^T * S^{-1} * y
 *
 * where y is the innovation (measurement residual) and S is the innovation
 * covariance. This is much stronger than Euclidean distance because it
 * accounts for the shape and scale of the uncertainty ellipse.
 *
 * For 2 measurement dimensions (range, bearing), common chi-square thresholds:
 *   - 95% confidence: gamma = 5.991
 *   - 99% confidence: gamma = 9.210
 *   - 99.5%:          gamma = 10.597
 */
public class MahalanobisGate {

    private final double threshold;

    /**
     * @param threshold chi-square gating threshold (gamma). Observations with
     *                  d^2 > gamma are rejected. Default: 9.21 (99% for 2 DOF).
     */
    public MahalanobisGate(double threshold) {
        this.threshold = threshold;
    }

    /** Default: 99% gate for 2 measurement dimensions. */
    public MahalanobisGate() {
        this(9.21);
    }

    /**
     * Compute the squared Mahalanobis distance for a candidate association.
     *
     * @param innovation      2x1 innovation vector y = z - h(x_pred)
     * @param innovationCov   2x2 innovation covariance S = H*P*H^T + R
     * @return squared Mahalanobis distance d^2
     */
    public double squaredDistance(SimpleMatrix innovation, SimpleMatrix innovationCov) {
        SimpleMatrix sInv = innovationCov.invert();
        // d^2 = y^T * S^{-1} * y  (scalar result from 1x2 * 2x2 * 2x1)
        SimpleMatrix result = innovation.transpose().mult(sInv).mult(innovation);
        return result.get(0, 0);
    }

    /**
     * Test whether a candidate passes the gate.
     *
     * @return true if d^2 <= threshold (plausible association)
     */
    public boolean isInsideGate(SimpleMatrix innovation, SimpleMatrix innovationCov) {
        return squaredDistance(innovation, innovationCov) <= threshold;
    }

    /**
     * Test with precomputed d^2.
     */
    public boolean isInsideGate(double squaredDist) {
        return squaredDist <= threshold;
    }

    public double getThreshold() { return threshold; }
}
