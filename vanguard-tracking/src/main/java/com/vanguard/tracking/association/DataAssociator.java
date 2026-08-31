package com.vanguard.tracking.association;

import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import org.ejml.simple.SimpleMatrix;

import java.util.*;

/**
 * Nearest-neighbour data association with spatial candidate pruning.
 *
 * v1.1: Before computing Mahalanobis distance (expensive: matrix operations),
 * the associator uses a SpatialGrid to cull tracks that are geometrically
 * impossible matches. Only tracks in the 9-cell neighborhood around the
 * observation's Cartesian position are tested.
 *
 * This reduces association cost from O(observations * all_tracks) to
 * O(observations * nearby_tracks). At 200 tracks with 300m cells,
 * nearby_tracks is typically 5-15 instead of 200.
 */
public class DataAssociator {

    private final MahalanobisGate gate;
    private final SpatialGrid spatialGrid;

    public DataAssociator(MahalanobisGate gate, double gridCellSize) {
        this.gate = gate;
        this.spatialGrid = new SpatialGrid(gridCellSize);
    }

    public DataAssociator(MahalanobisGate gate) {
        this(gate, 300.0); // 300m cells by default
    }

    public DataAssociator() {
        this(new MahalanobisGate(), 300.0);
    }

    /**
     * Result of an association attempt for one observation.
     */
    public sealed interface AssociationResult {
        record Associated(String trackId, double squaredDistance) implements AssociationResult {}
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
     * Rebuild the spatial index for this processing cycle. Call once before
     * associateBatch with all alive tracks' predicted positions.
     */
    public void rebuildIndex(List<Candidate> candidates) {
        spatialGrid.clear();
        for (Candidate c : candidates) {
            spatialGrid.insert(c.trackId(), c.filter().getPx(), c.filter().getPy());
        }
    }

    /**
     * Associate a single observation against candidates, using spatial pruning.
     */
    public AssociationResult associate(SimpleMatrix measurement,
                                        MeasurementModel sensorModel,
                                        List<Candidate> candidates,
                                        Map<String, Candidate> candidateMap) {
        // Convert observation to Cartesian for spatial query
        double range = measurement.get(0);
        double bearing = measurement.get(1);
        double obsPx = sensorModel.getSx() + range * Math.cos(bearing);
        double obsPy = sensorModel.getSy() + range * Math.sin(bearing);

        // Spatial pruning: only test nearby tracks
        List<String> nearbyIds = spatialGrid.queryNearby(obsPx, obsPy);

        String bestTrackId = null;
        double bestDist = Double.MAX_VALUE;

        for (String trackId : nearbyIds) {
            Candidate c = candidateMap.get(trackId);
            if (c == null) continue;

            SimpleMatrix[] innov = c.filter().computeInnovation(measurement, sensorModel);
            double d2 = gate.squaredDistance(innov[0], innov[1]);

            if (gate.isInsideGate(d2) && d2 < bestDist) {
                bestDist = d2;
                bestTrackId = trackId;
            }
        }

        if (bestTrackId != null) {
            return new AssociationResult.Associated(bestTrackId, bestDist);
        }
        return new AssociationResult.Unassociated();
    }

    /**
     * Legacy: associate without spatial pruning (tests all candidates).
     * Used when no index is built.
     */
    public AssociationResult associate(SimpleMatrix measurement,
                                        MeasurementModel sensorModel,
                                        List<Candidate> candidates) {
        String bestTrackId = null;
        double bestDist = Double.MAX_VALUE;

        for (Candidate c : candidates) {
            SimpleMatrix[] innov = c.filter().computeInnovation(measurement, sensorModel);
            double d2 = gate.squaredDistance(innov[0], innov[1]);

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
     * Batch association with spatial pruning. Greedy nearest-neighbour:
     * each track can be claimed by at most one observation per batch.
     */
    public Map<Integer, AssociationResult> associateBatch(
            List<SimpleMatrix> measurements,
            MeasurementModel sensorModel,
            List<Candidate> candidates) {

        // Build candidate lookup and spatial index
        Map<String, Candidate> candidateMap = new LinkedHashMap<>();
        for (Candidate c : candidates) candidateMap.put(c.trackId(), c);
        rebuildIndex(candidates);

        Set<String> claimed = new HashSet<>();
        Map<Integer, AssociationResult> results = new LinkedHashMap<>();

        for (int i = 0; i < measurements.size(); i++) {
            // Remove claimed tracks from the map for this observation
            AssociationResult result = associate(measurements.get(i), sensorModel,
                    candidates, candidateMap);

            if (result instanceof AssociationResult.Associated a) {
                if (claimed.contains(a.trackId())) {
                    // Track already taken, try without it
                    results.put(i, new AssociationResult.Unassociated());
                } else {
                    claimed.add(a.trackId());
                    results.put(i, result);
                }
            } else {
                results.put(i, result);
            }
        }

        return results;
    }

    public MahalanobisGate getGate() { return gate; }
    public SpatialGrid getSpatialGrid() { return spatialGrid; }
}
