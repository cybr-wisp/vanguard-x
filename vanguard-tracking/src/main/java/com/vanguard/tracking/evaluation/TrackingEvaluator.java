package com.vanguard.tracking.evaluation;

import com.vanguard.tracking.lifecycle.Track;

import java.util.*;

/**
 * Evaluates tracking quality by matching canonical tracks back to hidden
 * ground-truth target IDs. This harness is used for scoring only and never
 * feeds information back into the tracker.
 *
 * Metrics computed:
 *   - Position RMSE: sqrt(mean((px_hat - px_true)^2 + (py_hat - py_true)^2))
 *   - Velocity RMSE: same for velocity components
 *   - Association accuracy: fraction of reports assigned to the correct target
 *   - Track fragmentation: ground-truth targets mapped to multiple canonical tracks
 *   - False-track rate: canonical tracks that don't match any ground-truth target
 */
public class TrackingEvaluator {

    /** A single evaluation sample pairing a track estimate with ground truth. */
    public record Sample(
            String trackId,
            String truthTargetId,
            long timestampMs,
            double estPx, double estPy, double estVx, double estVy,
            double truePx, double truePy, double trueVx, double trueVy
    ) {}

    /** Aggregated evaluation results. */
    public record EvaluationResult(
            double positionRmse,
            double velocityRmse,
            double associationAccuracy,
            int trackFragmentation,
            int falseTracks,
            int totalSamples,
            int totalTruthTargets,
            int totalCanonicalTracks
    ) {
        @Override
        public String toString() {
            return ("posRMSE=%.2f velRMSE=%.2f assocAcc=%.1f%% " +
                    "fragmentation=%d falseTracks=%d " +
                    "samples=%d truthTargets=%d canonTracks=%d").formatted(
                    positionRmse, velocityRmse, associationAccuracy * 100,
                    trackFragmentation, falseTracks,
                    totalSamples, totalTruthTargets, totalCanonicalTracks);
        }
    }

    private final List<Sample> samples = new ArrayList<>();
    // Track-to-truth assignment: each canonical track -> which truth target it best matches
    private final Map<String, String> trackToTruth = new HashMap<>();
    private int correctAssociations = 0;
    private int totalAssociations = 0;

    /**
     * Record one evaluation sample. Called each time a track is updated and
     * the ground truth is known for comparison.
     *
     * @param trackId         canonical track ID
     * @param truthTargetId   hidden ground-truth target ID (from the simulator)
     * @param timestampMs     observation time
     * @param estPx, estPy    estimated position
     * @param estVx, estVy    estimated velocity
     * @param truePx, truePy  true position
     * @param trueVx, trueVy  true velocity
     */
    public void record(String trackId, String truthTargetId, long timestampMs,
                        double estPx, double estPy, double estVx, double estVy,
                        double truePx, double truePy, double trueVx, double trueVy) {
        samples.add(new Sample(trackId, truthTargetId, timestampMs,
                estPx, estPy, estVx, estVy, truePx, truePy, trueVx, trueVy));
    }

    /**
     * Record an association decision for accuracy tracking.
     *
     * @param assignedTrackId  the canonical track the observation was assigned to
     * @param truthTargetId    the actual ground-truth target that generated the observation
     */
    public void recordAssociation(String assignedTrackId, String truthTargetId) {
        totalAssociations++;
        // Assign truth to track by majority vote
        String existingTruth = trackToTruth.get(assignedTrackId);
        if (existingTruth == null) {
            trackToTruth.put(assignedTrackId, truthTargetId);
        }
        // Check if this assignment matches the track's majority truth
        if (truthTargetId.equals(trackToTruth.get(assignedTrackId))) {
            correctAssociations++;
        }
    }

    /**
     * Compute all evaluation metrics from recorded samples.
     */
    public EvaluationResult evaluate() {
        if (samples.isEmpty()) {
            return new EvaluationResult(0, 0, 0, 0, 0, 0, 0, 0);
        }

        // Position and velocity RMSE
        double sumPosErr2 = 0, sumVelErr2 = 0;
        for (Sample s : samples) {
            sumPosErr2 += (s.estPx - s.truePx) * (s.estPx - s.truePx)
                        + (s.estPy - s.truePy) * (s.estPy - s.truePy);
            sumVelErr2 += (s.estVx - s.trueVx) * (s.estVx - s.trueVx)
                        + (s.estVy - s.trueVy) * (s.estVy - s.trueVy);
        }
        double posRmse = Math.sqrt(sumPosErr2 / samples.size());
        double velRmse = Math.sqrt(sumVelErr2 / samples.size());

        // Association accuracy
        double assocAcc = totalAssociations > 0
                ? (double) correctAssociations / totalAssociations : 0;

        // Track fragmentation: count of truth targets mapped to >1 canonical track
        Map<String, Set<String>> truthToTracks = new HashMap<>();
        for (var entry : trackToTruth.entrySet()) {
            truthToTracks.computeIfAbsent(entry.getValue(), k -> new HashSet<>())
                    .add(entry.getKey());
        }
        int fragmentation = (int) truthToTracks.values().stream()
                .filter(set -> set.size() > 1)
                .count();

        // False tracks: canonical tracks not matched to any real truth target
        Set<String> allTruth = new HashSet<>();
        for (Sample s : samples) allTruth.add(s.truthTargetId);
        int falseTracks = (int) trackToTruth.values().stream()
                .filter(tid -> tid.startsWith("__FALSE__") || !allTruth.contains(tid))
                .count();

        Set<String> canonTracks = new HashSet<>();
        for (Sample s : samples) canonTracks.add(s.trackId);

        return new EvaluationResult(
                posRmse, velRmse, assocAcc,
                fragmentation, falseTracks,
                samples.size(), allTruth.size(), canonTracks.size());
    }

    /** Raw samples for CSV export. */
    public List<Sample> getSamples() { return Collections.unmodifiableList(samples); }

    /** Reset for a new evaluation run. */
    public void reset() {
        samples.clear();
        trackToTruth.clear();
        correctAssociations = 0;
        totalAssociations = 0;
    }
}
