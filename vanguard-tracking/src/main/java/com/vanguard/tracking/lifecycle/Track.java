package com.vanguard.tracking.lifecycle;

import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import com.vanguard.tracking.estimation.MotionModel;
import org.ejml.simple.SimpleMatrix;

import java.util.*;

/**
 * A single canonical track. Wraps an EKF with lifecycle state management,
 * hit/miss counting, and source (sensor) bookkeeping.
 */
public class Track {

    private final String trackId;
    private final ExtendedKalmanFilter ekf;
    private final MotionModel motionModel;
    private TrackState state;
    private long lastUpdateMs;
    private int consecutiveHits;
    private int consecutiveMisses;
    private int totalHits;
    private long lastHitMs;
    private final Set<String> contributingSensors = new HashSet<>();

    // Configurable thresholds
    private final int hitsToConfirm;
    private final int missesToCoast;
    private final int missesToDrop;

    public Track(String trackId, ExtendedKalmanFilter ekf, MotionModel motionModel,
                 long creationTimeMs, int hitsToConfirm, int missesToCoast, int missesToDrop) {
        this.trackId = trackId;
        this.ekf = ekf;
        this.motionModel = motionModel;
        this.state = TrackState.TENTATIVE;
        this.lastUpdateMs = creationTimeMs;
        this.consecutiveHits = 1;  // the observation that created the track counts
        this.consecutiveMisses = 0;
        this.totalHits = 1;
        this.lastHitMs = creationTimeMs;
        this.hitsToConfirm = hitsToConfirm;
        this.missesToCoast = missesToCoast;
        this.missesToDrop = missesToDrop;
    }

    /**
     * Predict the track state forward to the given time.
     */
    public void predictTo(long timeMs) {
        double dt = (timeMs - lastUpdateMs) / 1000.0;
        if (dt > 0) {
            ekf.predict(dt);
            lastUpdateMs = timeMs;
        }
    }

    /**
     * Update the track with a new observation and advance lifecycle.
     */
    public void update(SimpleMatrix measurement, MeasurementModel sensorModel,
                       String sensorId, long observationMs) {
        // Predict to observation time first
        double dt = (observationMs - lastUpdateMs) / 1000.0;
        if (dt > 0) {
            ekf.predict(dt);
        }

        ekf.update(measurement, sensorModel);
        lastUpdateMs = observationMs;
        contributingSensors.add(sensorId);

        /*
         * Multiple sensors may update this track at the same timestamp.
         * Those measurements all improve the EKF, but together they represent
         * one lifecycle hit, not several independent time cycles.
         */
        if (observationMs > lastHitMs) {
            lastHitMs = observationMs;
            consecutiveHits++;
            consecutiveMisses = 0;
            totalHits++;

            // Lifecycle transitions on one new observation cycle hit.
            switch (state) {
                case TENTATIVE -> {
                    if (consecutiveHits >= hitsToConfirm) {
                        state = TrackState.CONFIRMED;
                    }
                }
                case COASTING -> state = TrackState.CONFIRMED;
                case CONFIRMED -> {}
                case DROPPED -> {}
            }
        }
    }

    /**
     * Record a missed detection cycle (no observation associated this tick).
     */
    public void recordMiss(long currentMs) {
        // Predict forward
        double dt = (currentMs - lastUpdateMs) / 1000.0;
        if (dt > 0) {
            ekf.predict(dt);
            lastUpdateMs = currentMs;
        }

        consecutiveMisses++;
        consecutiveHits = 0;

        // Lifecycle transitions on miss
        switch (state) {
            case TENTATIVE -> {
                if (consecutiveMisses >= missesToDrop) {
                    state = TrackState.DROPPED;
                }
            }
            case CONFIRMED -> {
                if (consecutiveMisses >= missesToCoast) {
                    state = TrackState.COASTING;
                }
            }
            case COASTING -> {
                if (consecutiveMisses >= missesToDrop) {
                    state = TrackState.DROPPED;
                }
            }
            case DROPPED -> {} // terminal
        }
    }

    // ---- Accessors ----

    public String getTrackId()           { return trackId; }
    public TrackState getState()         { return state; }
    public ExtendedKalmanFilter getEkf() { return ekf; }
    public long getLastUpdateMs()        { return lastUpdateMs; }
    public int getConsecutiveHits()      { return consecutiveHits; }
    public int getConsecutiveMisses()    { return consecutiveMisses; }
    public int getTotalHits()            { return totalHits; }
    public Set<String> getContributingSensors() { return Collections.unmodifiableSet(contributingSensors); }

    public void addContributingSensor(String sensorId) {
        if (sensorId != null && !sensorId.isBlank()) {
            contributingSensors.add(sensorId);
        }
    }
    public boolean isAlive()             { return state != TrackState.DROPPED; }

    public double getPx() { return ekf.getPx(); }
    public double getPy() { return ekf.getPy(); }
    public double getVx() { return ekf.getVx(); }
    public double getVy() { return ekf.getVy(); }
    public double getPositionUncertainty() { return ekf.getPositionUncertainty(); }
}
