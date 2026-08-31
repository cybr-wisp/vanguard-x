package com.vanguard.simulator;

import java.util.Random;

/**
 * Adds sensor-specific Gaussian noise and systematic bias to range and bearing
 * measurements. Each sensor has its own NoiseModel instance so observations
 * of the same target differ across sensors.
 *
 * Noise is zero-mean Gaussian scaled by the configured sigma. Bias is a fixed
 * offset that persists for the sensor's lifetime (simulating calibration error).
 */
public class NoiseModel {

    private final Random rng;
    private final double sigmaRange;
    private final double sigmaBearing;
    private final double biasRange;
    private final double biasBearing;

    public NoiseModel(Random rng, double sigmaRange, double sigmaBearing,
                      double biasRange, double biasBearing) {
        this.rng = rng;
        this.sigmaRange = sigmaRange;
        this.sigmaBearing = sigmaBearing;
        this.biasRange = biasRange;
        this.biasBearing = biasBearing;
    }

    /** Apply noise + bias to a true range value. */
    public double corruptRange(double trueRange) {
        return trueRange + biasRange + rng.nextGaussian() * sigmaRange;
    }

    /** Apply noise + bias to a true bearing value. */
    public double corruptBearing(double trueBearing) {
        return trueBearing + biasBearing + rng.nextGaussian() * sigmaBearing;
    }

    public double getSigmaRange()   { return sigmaRange; }
    public double getSigmaBearing() { return sigmaBearing; }
}
