package com.vanguard.tracking.association;

import com.vanguard.tracking.estimation.ExtendedKalmanFilter;
import com.vanguard.tracking.estimation.MeasurementModel;
import org.ejml.simple.SimpleMatrix;

import java.util.*;

/**
 * Mahalanobis data association with spatial candidate pruning.
 *
 * Single-observation association uses nearest-neighbour Mahalanobis distance.
 *
 * Batch association builds a gated observation/track cost matrix and solves a
 * global minimum-cost one-to-one assignment. This avoids observation-order
 * artifacts where an early flexible observation claims a track that is the
 * only valid match for a later observation.
 *
 * SpatialGrid remains a coarse candidate-pruning stage. Mahalanobis gating is
 * the statistical acceptance test.
 */
public class DataAssociator {

    private final MahalanobisGate gate;
    private final SpatialGrid spatialGrid;

    public DataAssociator(
            MahalanobisGate gate,
            double gridCellSize) {

        this.gate = gate;
        this.spatialGrid =
                new SpatialGrid(gridCellSize);
    }

    public DataAssociator(MahalanobisGate gate) {
        this(gate, 300.0);
    }

    public DataAssociator() {
        this(
                new MahalanobisGate(),
                300.0
        );
    }

    /**
     * Result of an association attempt for one observation.
     */
    public sealed interface AssociationResult {

        record Associated(
                String trackId,
                double squaredDistance)
                implements AssociationResult {}

        record Unassociated()
                implements AssociationResult {}
    }

    /**
     * A candidate track that can be tested for association.
     */
    public record Candidate(
            String trackId,
            ExtendedKalmanFilter filter
    ) {}

    /**
     * Rebuild the spatial index for this processing cycle.
     */
    public void rebuildIndex(
            List<Candidate> candidates) {

        spatialGrid.clear();

        for (Candidate candidate : candidates) {
            spatialGrid.insert(
                    candidate.trackId(),
                    candidate.filter().getPx(),
                    candidate.filter().getPy()
            );
        }
    }

    /**
     * Associate one observation against a supplied candidate map using
     * spatial pruning.
     */
    public AssociationResult associate(
            SimpleMatrix measurement,
            MeasurementModel sensorModel,
            List<Candidate> candidates,
            Map<String, Candidate> candidateMap) {

        double range =
                measurement.get(0);

        double bearing =
                measurement.get(1);

        double obsPx =
                sensorModel.getSx() +
                        range * Math.cos(bearing);

        double obsPy =
                sensorModel.getSy() +
                        range * Math.sin(bearing);

        List<String> nearbyIds =
                spatialGrid.queryNearby(
                        obsPx,
                        obsPy
                );

        String bestTrackId = null;
        double bestDistance =
                Double.MAX_VALUE;

        for (String trackId : nearbyIds) {

            Candidate candidate =
                    candidateMap.get(trackId);

            if (candidate == null) {
                continue;
            }

            SimpleMatrix[] innovation =
                    candidate.filter()
                            .computeInnovation(
                                    measurement,
                                    sensorModel
                            );

            double squaredDistance =
                    gate.squaredDistance(
                            innovation[0],
                            innovation[1]
                    );

            if (gate.isInsideGate(squaredDistance)
                    && squaredDistance < bestDistance) {

                bestDistance =
                        squaredDistance;

                bestTrackId =
                        trackId;
            }
        }

        if (bestTrackId != null) {
            return new AssociationResult.Associated(
                    bestTrackId,
                    bestDistance
            );
        }

        return new AssociationResult.Unassociated();
    }

    /**
     * Legacy single-observation association without spatial pruning.
     */
    public AssociationResult associate(
            SimpleMatrix measurement,
            MeasurementModel sensorModel,
            List<Candidate> candidates) {

        String bestTrackId = null;
        double bestDistance =
                Double.MAX_VALUE;

        for (Candidate candidate : candidates) {

            SimpleMatrix[] innovation =
                    candidate.filter()
                            .computeInnovation(
                                    measurement,
                                    sensorModel
                            );

            double squaredDistance =
                    gate.squaredDistance(
                            innovation[0],
                            innovation[1]
                    );

            if (gate.isInsideGate(squaredDistance)
                    && squaredDistance < bestDistance) {

                bestDistance =
                        squaredDistance;

                bestTrackId =
                        candidate.trackId();
            }
        }

        if (bestTrackId != null) {
            return new AssociationResult.Associated(
                    bestTrackId,
                    bestDistance
            );
        }

        return new AssociationResult.Unassociated();
    }

    /**
     * Global batch association.
     *
     * Rows are observations.
     * Real columns are candidate tracks.
     * Extra dummy columns represent leaving an observation unassociated.
     *
     * Every valid observation/track edge is weighted by squared Mahalanobis
     * distance. Gated-out pairs receive a prohibitive cost.
     *
     * The dummy penalty is deliberately larger than the maximum possible
     * aggregate gated cost so the solution first maximizes the number of
     * legitimate associations, then minimizes their total Mahalanobis cost.
     */
    public Map<Integer, AssociationResult> associateBatch(
            List<SimpleMatrix> measurements,
            MeasurementModel sensorModel,
            List<Candidate> candidates) {

        Map<Integer, AssociationResult> results =
                new LinkedHashMap<>();

        if (measurements.isEmpty()) {
            return results;
        }

        if (candidates.isEmpty()) {
            for (int i = 0;
                 i < measurements.size();
                 i++) {

                results.put(
                        i,
                        new AssociationResult.Unassociated()
                );
            }

            return results;
        }

        rebuildIndex(candidates);

        Map<String, Integer> candidateIndexes =
                new HashMap<>();

        for (int i = 0;
             i < candidates.size();
             i++) {

            candidateIndexes.put(
                    candidates.get(i).trackId(),
                    i
            );
        }

        int observationCount =
                measurements.size();

        int candidateCount =
                candidates.size();

        /*
         * Add one dummy assignment column per observation. Therefore the
         * matrix always has at least as many columns as rows, which is the
         * rectangular form required by the Hungarian solver below.
         */
        int columnCount =
                candidateCount +
                        observationCount;

        double gateThreshold =
                gate.getThreshold();

        double unassociatedCost =
                (observationCount
                        + candidateCount
                        + 1.0)
                        * (gateThreshold + 1.0);

        double forbiddenCost =
                unassociatedCost *
                        1_000.0;

        double[][] costs =
                new double[
                        observationCount
                ][
                        columnCount
                ];

        for (int observationIndex = 0;
             observationIndex < observationCount;
             observationIndex++) {

            Arrays.fill(
                    costs[observationIndex],
                    forbiddenCost
            );

            /*
             * Any dummy column may represent an unassociated observation.
             * There are enough dummy columns for every row.
             */
            for (int column = candidateCount;
                 column < columnCount;
                 column++) {

                costs[observationIndex][column] =
                        unassociatedCost;
            }

            SimpleMatrix measurement =
                    measurements.get(
                            observationIndex
                    );

            double range =
                    measurement.get(0);

            double bearing =
                    measurement.get(1);

            double obsPx =
                    sensorModel.getSx() +
                            range *
                                    Math.cos(bearing);

            double obsPy =
                    sensorModel.getSy() +
                            range *
                                    Math.sin(bearing);

            List<String> nearbyIds =
                    spatialGrid.queryNearby(
                            obsPx,
                            obsPy
                    );

            for (String trackId : nearbyIds) {

                Integer candidateIndex =
                        candidateIndexes.get(
                                trackId
                        );

                if (candidateIndex == null) {
                    continue;
                }

                Candidate candidate =
                        candidates.get(
                                candidateIndex
                        );

                SimpleMatrix[] innovation =
                        candidate.filter()
                                .computeInnovation(
                                        measurement,
                                        sensorModel
                                );

                double squaredDistance =
                        gate.squaredDistance(
                                innovation[0],
                                innovation[1]
                        );

                if (gate.isInsideGate(
                        squaredDistance)) {

                    costs[
                            observationIndex
                    ][
                            candidateIndex
                    ] =
                            Math.min(
                                    costs[
                                            observationIndex
                                    ][
                                            candidateIndex
                                    ],
                                    squaredDistance
                            );
                }
            }
        }

        int[] assignment =
                solveMinimumCostAssignment(
                        costs
                );

        for (int observationIndex = 0;
             observationIndex < observationCount;
             observationIndex++) {

            int assignedColumn =
                    assignment[
                            observationIndex
                    ];

            if (assignedColumn >= 0
                    && assignedColumn < candidateCount
                    && gate.isInsideGate(
                            costs[
                                    observationIndex
                            ][
                                    assignedColumn
                            ])) {

                Candidate candidate =
                        candidates.get(
                                assignedColumn
                        );

                results.put(
                        observationIndex,
                        new AssociationResult.Associated(
                                candidate.trackId(),
                                costs[
                                        observationIndex
                                ][
                                        assignedColumn
                                ]
                        )
                );

            } else {

                results.put(
                        observationIndex,
                        new AssociationResult.Unassociated()
                );
            }
        }

        return results;
    }

    /**
     * Hungarian algorithm for rectangular minimum-cost assignment.
     *
     * Requires rows <= columns. associateBatch guarantees this by adding
     * dummy unassociated columns.
     *
     * @return assigned column for every row
     */
    private int[] solveMinimumCostAssignment(
            double[][] costs) {

        int rows =
                costs.length;

        if (rows == 0) {
            return new int[0];
        }

        int columns =
                costs[0].length;

        if (rows > columns) {
            throw new IllegalArgumentException(
                    "Hungarian assignment requires rows <= columns"
            );
        }

        double[] rowPotential =
                new double[
                        rows + 1
                ];

        double[] columnPotential =
                new double[
                        columns + 1
                ];

        int[] columnMatch =
                new int[
                        columns + 1
                ];

        int[] predecessor =
                new int[
                        columns + 1
                ];

        for (int row = 1;
             row <= rows;
             row++) {

            columnMatch[0] =
                    row;

            int currentColumn =
                    0;

            double[] minimumReducedCost =
                    new double[
                            columns + 1
                    ];

            Arrays.fill(
                    minimumReducedCost,
                    Double.POSITIVE_INFINITY
            );

            boolean[] used =
                    new boolean[
                            columns + 1
                    ];

            do {
                used[currentColumn] =
                        true;

                int currentRow =
                        columnMatch[
                                currentColumn
                        ];

                double delta =
                        Double.POSITIVE_INFINITY;

                int nextColumn =
                        0;

                for (int column = 1;
                     column <= columns;
                     column++) {

                    if (used[column]) {
                        continue;
                    }

                    double reducedCost =
                            costs[
                                    currentRow - 1
                            ][
                                    column - 1
                            ]
                                    - rowPotential[
                                            currentRow
                                    ]
                                    - columnPotential[
                                            column
                                    ];

                    if (reducedCost
                            < minimumReducedCost[
                                    column
                            ]) {

                        minimumReducedCost[
                                column
                        ] =
                                reducedCost;

                        predecessor[
                                column
                        ] =
                                currentColumn;
                    }

                    if (minimumReducedCost[
                            column
                    ] < delta) {

                        delta =
                                minimumReducedCost[
                                        column
                                ];

                        nextColumn =
                                column;
                    }
                }

                for (int column = 0;
                     column <= columns;
                     column++) {

                    if (used[column]) {

                        rowPotential[
                                columnMatch[
                                        column
                                ]
                        ] += delta;

                        columnPotential[
                                column
                        ] -= delta;

                    } else if (column > 0) {

                        minimumReducedCost[
                                column
                        ] -= delta;
                    }
                }

                currentColumn =
                        nextColumn;

            } while (columnMatch[
                    currentColumn
            ] != 0);

            do {
                int previousColumn =
                        predecessor[
                                currentColumn
                        ];

                columnMatch[
                        currentColumn
                ] =
                        columnMatch[
                                previousColumn
                        ];

                currentColumn =
                        previousColumn;

            } while (currentColumn != 0);
        }

        int[] assignment =
                new int[
                        rows
                ];

        Arrays.fill(
                assignment,
                -1
        );

        for (int column = 1;
             column <= columns;
             column++) {

            int matchedRow =
                    columnMatch[
                            column
                    ];

            if (matchedRow != 0) {
                assignment[
                        matchedRow - 1
                ] =
                        column - 1;
            }
        }

        return assignment;
    }

    public MahalanobisGate getGate() {
        return gate;
    }

    public SpatialGrid getSpatialGrid() {
        return spatialGrid;
    }
}