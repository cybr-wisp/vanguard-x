package com.vanguard.tracking.lifecycle;

/**
 * Track lifecycle states per the build guide Section 1.5.
 *
 * TENTATIVE  -> A new hypothesis. Requires repeated supporting observations
 *               before promotion to CONFIRMED.
 * CONFIRMED  -> A stable track with sufficient supporting observations.
 * COASTING   -> Temporarily missing observations. Predict forward while
 *               uncertainty grows. Can return to CONFIRMED on reacquisition.
 * DROPPED    -> Expired after a configurable number of misses or excessive
 *               uncertainty. Terminal state.
 */
public enum TrackState {
    TENTATIVE,
    CONFIRMED,
    COASTING,
    DROPPED
}
