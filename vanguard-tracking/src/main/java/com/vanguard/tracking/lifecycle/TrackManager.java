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

        // 1. Build candidate list from alive tracks
        List<Candidate> candidates = new ArrayList<>();
        for (Track t : tracks.values()) {
            if (t.isAlive()) {
                // Predict a copy to observation time for gating (don't modify original yet)
                candidates.add(new Candidate(t.getTrackId(), t.getEkf()));
            }
        }

        // 2. Associate
        Map<Integer, AssociationResult> results =
                associator.associateBatch(measurements, sensorModel, candidates);

        // 3. Track which tracks got updated
        Set<String> updatedTracks = new HashSet<>();

        for (var entry : results.entrySet()) {
            int obsIdx = entry.getKey();
            AssociationResult result = entry.getValue();

            if (result instanceof AssociationResult.Associated a) {
                // Update the associated track
                Track track = tracks.get(a.trackId());
                if (track != null) {
                    track.update(measurements.get(obsIdx), sensorModel, sensorId, observationMs);
                    updatedTracks.add(a.trackId());
                }
            } else {
                // Unassociated: create a new tentative track
                initializeTrack(measurements.get(obsIdx), sensorModel, sensorId, observationMs);
            }
        }

        // 4. Record misses for alive tracks that were not updated
        for (Track t : tracks.values()) {
            if (t.isAlive() && !updatedTracks.contains(t.getTrackId())) {
                t.recordMiss(observationMs);
            }
        }

        return results;
    }

    /**
     * Initialize a new tentative track from an unassociated observation.
     * Position is derived from the sensor position + measurement (range/bearing).
     * Velocity is initialized to zero with high uncertainty.
     */
    private void initializeTrack(SimpleMatrix measurement, MeasurementModel sensorModel,
                                  String sensorId, long observationMs) {
        double range   = measurement.get(0);
        double bearing = measurement.get(1);

        double px = sensorModel.getSx() + range * Math.cos(bearing);
        double py = sensorModel.getSy() + range * Math.sin(bearing);

        SimpleMatrix x0 = new SimpleMatrix(new double[][]{{px}, {py}, {0}, {0}});
        SimpleMatrix P0 = SimpleMatrix.identity(4);
        P0.set(0, 0, initialPositionVar);
        P0.set(1, 1, initialPositionVar);
        P0.set(2, 2, initialVelocityVar);
        P0.set(3, 3, initialVelocityVar);

        ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, motionModel);
        String trackId = "TRK-%06d".formatted(trackIdCounter.incrementAndGet());

        Track track = new Track(trackId, ekf, motionModel, observationMs,
                hitsToConfirm, missesToCoast, missesToDrop);
        track.getContributingSensors().getClass(); // no-op, sensors added in constructor via first hit
        tracks.put(trackId, track);
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
