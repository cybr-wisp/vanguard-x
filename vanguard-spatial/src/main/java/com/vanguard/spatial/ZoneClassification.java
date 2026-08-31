package com.vanguard.spatial;

/**
 * Classification of a track's relationship to a restricted zone.
 * Ordered by severity; ordinal comparisons are meaningful.
 */
public enum ZoneClassification {
    /** Track is outside the advisory buffer. */
    CLEAR,
    /** Track is within the advisory buffer but outside the warning buffer. */
    ADVISORY,
    /** Track is within the warning buffer but outside the zone boundary. */
    WARNING,
    /** Track is inside the restricted zone. */
    BREACH
}
