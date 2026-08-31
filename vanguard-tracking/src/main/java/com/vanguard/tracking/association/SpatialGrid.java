package com.vanguard.tracking.association;

import java.util.*;

/**
 * Grid-based spatial index for fast candidate pruning before Mahalanobis gating.
 *
 * Divides the tracking area into fixed-size cells. Each track is assigned to
 * the cell containing its predicted position. When associating an observation,
 * only tracks in the 9 neighboring cells (3x3 block) are tested as candidates.
 *
 * This reduces association from O(observations * all_tracks) to
 * O(observations * nearby_tracks), where nearby_tracks is typically 5-15
 * instead of hundreds.
 *
 * The grid is rebuilt each processing cycle (cheap: one hash-map insert per track).
 */
public class SpatialGrid {

    private final double cellSize;
    private final Map<Long, List<String>> grid = new HashMap<>();
    private final Map<String, double[]> positions = new HashMap<>();

    /**
     * @param cellSize side length of each grid cell in meters.
     *                 Should be roughly 2-3x the maximum gating distance.
     *                 200-500m is typical.
     */
    public SpatialGrid(double cellSize) {
        this.cellSize = cellSize;
    }

    /**
     * Clear the grid for a new processing cycle.
     */
    public void clear() {
        grid.clear();
        positions.clear();
    }

    /**
     * Insert a track's predicted position into the grid.
     */
    public void insert(String trackId, double px, double py) {
        long key = cellKey(px, py);
        grid.computeIfAbsent(key, k -> new ArrayList<>()).add(trackId);
        positions.put(trackId, new double[]{px, py});
    }

    /**
     * Query for track IDs near the given observation position.
     * Returns tracks in the 9-cell neighborhood (3x3 block).
     */
    public List<String> queryNearby(double px, double py) {
        int cx = cellX(px), cy = cellY(py);
        List<String> result = new ArrayList<>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                long key = packKey(cx + dx, cy + dy);
                List<String> cell = grid.get(key);
                if (cell != null) {
                    result.addAll(cell);
                }
            }
        }
        return result;
    }

    /**
     * Get the number of occupied cells (for diagnostics).
     */
    public int getOccupiedCells() {
        return grid.size();
    }

    /**
     * Get the total number of indexed tracks.
     */
    public int getTrackCount() {
        return positions.size();
    }

    private int cellX(double px) { return (int) Math.floor(px / cellSize); }
    private int cellY(double py) { return (int) Math.floor(py / cellSize); }
    private long cellKey(double px, double py) { return packKey(cellX(px), cellY(py)); }
    private long packKey(int cx, int cy) { return ((long) cx << 32) | (cy & 0xFFFFFFFFL); }
}
