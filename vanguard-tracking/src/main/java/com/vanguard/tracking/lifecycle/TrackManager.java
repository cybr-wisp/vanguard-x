package com.vanguard.tracking.lifecycle;

import com.vanguard.tracking.association.DataAssociator;
import com.vanguard.tracking.association.DataAssociator.AssociationResult;
import com.vanguard.tracking.association.DataAssociator.Candidate;
import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import com.vanguard.tracking.estimation.MotionModel;
import org.ejml.simple.SimpleMatrix;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the full set of canonical tracks. For each observation cycle:
 *   1. Predict all alive tracks to observation time
 *   2. Associate observations via DataAssociator
 *   3. Update associated tracks with the EKF
 *   4. Create new tentative tracks for unassociated observations
 *   5. Record misses for tracks that received no observation
 *   6. Prune dropped tracks
 */
public class TrackManager {

    private final Map<String, Track> tracks = new LinkedHashMap<>();
    private final DataAssociator associator;
    private final MotionModel motionModel;
    private final AtomicLong trackIdCounter = new AtomicLong(0);

    // Lifecycle thresholds
    private final int hitsToConfirm;
    private final int missesToCoast;
    private final int missesToDrop;

    // Initial covariance for new tracks
    private final double initialPositionVar;
    private final double initialVelocityVar;

    /*
     * All sensor batches sharing the same timestamp belong to one
     * lifecycle observation cycle.
     *
     * A track is missed only when the timestamp advances and no sensor
     * updated that track during the completed cycle.
     */
    private long activeObservationMs = Long.MIN_VALUE;
    private final Set<String> updatedInActiveCycle = new HashSet<>();

    public TrackManager(DataAssociator associator, MotionModel motionModel,
                        int hitsToConfirm, int missesToCoast, int missesToDrop,
                        double initialPositionVar, double initialVelocityVar) {
        this.associator = associator;
        this.motionModel = motionModel;
        this.hitsToConfirm = hitsToConfirm;
        this.missesToCoast = missesToCoast;
        this.missesToDrop = missesToDrop;
        this.initialPositionVar = initialPositionVar;
        this.initialVelocityVar = initialVelocityVar;
    }

    /** Convenience constructor with sensible defaults. */
    public TrackManager(DataAssociator associator, MotionModel motionModel) {
        this(associator, motionModel, 3, 3, 8, 10000, 100);
    }

    /**
     * Process a batch of observations from one sensor at one time.
     *
     * @param measurements  list of 2x1 [range, bearing] observations
     * @param sensorModel   measurement model for the observing sensor
     * @param sensorId      sensor identifier for source bookkeeping
     * @param observationMs timestamp of these observations
     * @return map of observation index to association result
     */
    public Map<Integer, AssociationResult> processObservations(
            List<SimpleMatrix> measurements,
            MeasurementModel sensorModel,
            String sensorId,
            long observationMs) {

        /*
         * Start a new lifecycle cycle only when the observation timestamp
         * advances. This also predicts every alive track to the observation
         * time before Mahalanobis gating.
         */
        beginObservationCycleIfNeeded(observationMs);

        // 1. Build candidate list from predicted alive tracks.
        List<Candidate> candidates = new ArrayList<>();
        for (Track t : tracks.values()) {
            if (t.isAlive()) {
                candidates.add(new Candidate(t.getTrackId(), t.getEkf()));
            }
        }

        // 2. Associate
        Map<Integer, AssociationResult> results =
                associator.associateBatch(measurements, sensorModel, candidates);


        for (var entry : results.entrySet()) {
            int obsIdx = entry.getKey();
            AssociationResult result = entry.getValue();

            if (result instanceof AssociationResult.Associated a) {
                // Update the associated track
                Track track = tracks.get(a.trackId());
                if (track != null) {
                    track.update(measurements.get(obsIdx), sensorModel, sensorId, observationMs);
                    updatedInActiveCycle.add(a.trackId());
                }
            } else {
                // Unassociated: create a new tentative track.
                // The creating observation counts as an update in this cycle,
                // so the new track must not immediately receive a miss.
                String newTrackId =
                        initializeTrack(
                                measurements.get(obsIdx),
                                sensorModel,
                                sensorId,
                                observationMs
                        );

                updatedInActiveCycle.add(newTrackId);
            }
        }

        /*
         * Misses are intentionally NOT recorded here.
         *
         * More sensor batches may still arrive with this exact timestamp.
         * The lifecycle cycle is finalized only when a newer timestamp is
         * observed.
         */
        return results;
    }

    /**
     * Start a new observation cycle when the timestamp advances.
     *
     * The previous cycle is finalized exactly once, irrespective of how many
     * sensors participated in it. Tracks are then predicted to the new
     * timestamp before association.
     */
    private void beginObservationCycleIfNeeded(long observationMs) {
        if (activeObservationMs == observationMs) {
            return;
        }

        if (activeObservationMs != Long.MIN_VALUE) {
            finalizeActiveObservationCycle();
        }

        activeObservationMs = observationMs;
        updatedInActiveCycle.clear();

        for (Track track : tracks.values()) {
            if (track.isAlive()) {
                track.predictTo(observationMs);
            }
        }
    }

    /**
     * Apply at most one lifecycle miss to each alive track for the completed
     * observation timestamp.
     */
    private void finalizeActiveObservationCycle() {
        for (Track track : tracks.values()) {
            if (track.isAlive()
                    && !updatedInActiveCycle.contains(track.getTrackId())) {

                track.recordMiss(activeObservationMs);
            }
        }

        updatedInActiveCycle.clear();
    }

    /**
     * Initialize a new tentative track from an unassociated observation.
     * Position is derived from the sensor position + measurement (range/bearing).
     * Velocity is initialized to zero with high uncertainty.
     */
    private String initializeTrack(SimpleMatrix measurement, MeasurementModel sensorModel,
                                   String sensorId, long observationMs) {
        double range   = measurement.get(0);
        double bearing = measurement.get(1);

        double cos = Math.cos(bearing);
        double sin = Math.sin(bearing);

        double px =
                sensorModel.getSx() +
                        range * cos;

        double py =
                sensorModel.getSy() +
                        range * sin;

        SimpleMatrix x0 =
                new SimpleMatrix(
                        new double[][]{
                                {px},
                                {py},
                                {0},
                                {0}
                        }
                );

        /*
         * Transform the sensor's polar measurement covariance
         * [range, bearing] into Cartesian position covariance.
         *
         * The bearing contribution grows with range, which is critical for
         * long-range sensors. A fixed isotropic position covariance makes a
         * newly-created track unrealistically confident and can cause another
         * sensor observing the same target to spawn a duplicate track.
         */
        SimpleMatrix measurementNoise =
                sensorModel.noiseCovariance();

        double rangeVariance =
                measurementNoise.get(0, 0);

        double bearingVariance =
                measurementNoise.get(1, 1);

        double rangeSquared =
                range * range;

        double varX =
                cos * cos * rangeVariance +
                        rangeSquared *
                                sin * sin *
                                bearingVariance;

        double varY =
                sin * sin * rangeVariance +
                        rangeSquared *
                                cos * cos *
                                bearingVariance;

        double covXY =
                sin * cos *
                        (
                                rangeVariance -
                                        rangeSquared *
                                                bearingVariance
                        );

        SimpleMatrix P0 =
                new SimpleMatrix(4, 4);

        /*
         * initialPositionVar is retained as a conservative Cartesian
         * uncertainty floor in addition to the propagated sensor noise.
         */
        P0.set(
                0,
                0,
                varX + initialPositionVar
        );

        P0.set(
                1,
                1,
                varY + initialPositionVar
        );

        P0.set(
                0,
                1,
                covXY
        );

        P0.set(
                1,
                0,
                covXY
        );

        P0.set(
                2,
                2,
                initialVelocityVar
        );

        P0.set(
                3,
                3,
                initialVelocityVar
        );

        ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, motionModel);
        String trackId = "TRK-%06d".formatted(trackIdCounter.incrementAndGet());

        Track track = new Track(trackId, ekf, motionModel, observationMs,
                hitsToConfirm, missesToCoast, missesToDrop);
        track.addContributingSensor(sensorId);
        tracks.put(trackId, track);

        return trackId;
    }

    /** Remove dropped tracks from memory. */
    public void pruneDropped() {
        tracks.entrySet().removeIf(e -> e.getValue().getState() == TrackState.DROPPED);
    }

    // ---- Accessors ----

    public Collection<Track> getAllTracks()  { return Collections.unmodifiableCollection(tracks.values()); }
    public Collection<Track> getAliveTracks() {
        return tracks.values().stream().filter(Track::isAlive).toList();
    }
    public Optional<Track> getTrack(String trackId) { return Optional.ofNullable(tracks.get(trackId)); }
    public int getAliveCount()    { return (int) tracks.values().stream().filter(Track::isAlive).count(); }
    public int getConfirmedCount(){ return (int) tracks.values().stream()
            .filter(t -> t.getState() == TrackState.CONFIRMED).count(); }
    public int getCoastingCount() { return (int) tracks.values().stream()
            .filter(t -> t.getState() == TrackState.COASTING).count(); }
    public int getTotalCreated()  { return (int) trackIdCounter.get(); }
}
