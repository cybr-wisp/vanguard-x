package com.vanguard.tracking.association;

import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import org.ejml.simple.SimpleMatrix;

import java.util.*;

/**
 * Nearest-neighbour data association inside a Mahalanobis gate.
 *
 * For each incoming observation, the associator:
 *   1. Predicts each candidate track to the observation time
 *   2. Computes the innovation and innovation covariance
 *   3. Tests whether d^2 falls inside the gate
 *   4. Selects the nearest candidate (lowest d^2) among those that pass
 *
 * This is the v1.0 association strategy per the build guide. JPDA/MHT
 * are explicitly deferred to future work.
 */
public class DataAssociator {

    private final MahalanobisGate gate;

    public DataAssociator(MahalanobisGate gate) {
        this.gate = gate;
    }

    public DataAssociator() {
        this(new MahalanobisGate());
    }

    /**
     * Result of an association attempt for one observation.
     */
    public sealed interface AssociationResult {
        /** Observation was associated to an existing track. */
        record Associated(String trackId, double squaredDistance) implements AssociationResult {}
        /** No track passed the gate; observation may initiate a new track. */
        record Unassociated() implements AssociationResult {}
    }

    /**
     * A candidate track that can be tested for association.
     */
    public record Candidate(
            String trackId,
            ExtendedKalmanFilter filter
    ) {}

    /**
     * Associate a single observation against a list of candidate tracks.
     * The candidates' filters should already be predicted to the observation time.
     *
     * @param measurement   2x1 [range, bearing] observation
     * @param sensorModel   measurement model for the observing sensor
     * @param candidates    list of candidate tracks (already predicted to obs time)
     * @return association result
     */
    public AssociationResult associate(SimpleMatrix measurement,
                                        MeasurementModel sensorModel,
                                        List<Candidate> candidates) {
        String bestTrackId = null;
        double bestDist = Double.MAX_VALUE;

        for (Candidate c : candidates) {
            SimpleMatrix[] innov = c.filter().computeInnovation(measurement, sensorModel);
            SimpleMatrix y = innov[0]; // innovation
            SimpleMatrix S = innov[1]; // innovation covariance

            double d2 = gate.squaredDistance(y, S);

            if (gate.isInsideGate(d2) && d2 < bestDist) {
                bestDist = d2;
                bestTrackId = c.trackId();
            }
        }

        if (bestTrackId != null) {
            return new AssociationResult.Associated(bestTrackId, bestDist);
        }
        return new AssociationResult.Unassociated();
    }

    /**
     * Batch association: associate multiple observations, each to at most one track.
     * Greedy nearest-neighbour: process observations in order, each track can be
     * claimed by at most one observation per batch.
     *
     * @return map of observation index to AssociationResult
     */
    public Map<Integer, AssociationResult> associateBatch(
            List<SimpleMatrix> measurements,
            MeasurementModel sensorModel,
            List<Candidate> candidates) {

        Set<String> claimed = new HashSet<>();
        Map<Integer, AssociationResult> results = new LinkedHashMap<>();

        for (int i = 0; i < measurements.size(); i++) {
            // Filter out already-claimed tracks
            List<Candidate> available = candidates.stream()
                    .filter(c -> !claimed.contains(c.trackId()))
                    .toList();

            AssociationResult result = associate(measurements.get(i), sensorModel, available);
            results.put(i, result);

            if (result instanceof AssociationResult.Associated a) {
                claimed.add(a.trackId());
            }
        }

        return results;
    }

    public MahalanobisGate getGate() { return gate; }
}
