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
     * Runtime duplicate suppression.
     *
     * The tracker is intentionally truth-ID blind. These thresholds operate
     * only on estimated kinematics and sensor provenance.
     */
    private static final double TENTATIVE_DUPLICATE_POSITION_M = 350.0;
    private static final double CONFIRMED_DUPLICATE_POSITION_M = 220.0;
    private static final double CONFIRMED_DUPLICATE_VELOCITY_MPS = 85.0;

    /*
     * All sensor batches sharing the same timestamp belong to one
     * lifecycle observation cycle.
     *
     * A track is missed only when the timestamp advances and no sensor
     * updated that track during the completed cycle.
     */
    private long activeObservationMs = Long.MIN_VALUE;
    private final Set<String> updatedInActiveCycle = new HashSet<>();
    private final Set<String> sensorsInActiveCycle = new HashSet<>();

    /*
     * Association for every sensor batch in one timestamp must use the same
     * predicted prior. Canonical EKFs may be updated immediately after a match,
     * but those posterior updates must not change the gate seen by later
     * sensors reporting the same observation time.
     */
    private List<Candidate> associationCandidates = List.of();

    /*
     * Track births are deferred until the observation timestamp closes.
     *
     * Each sensor still performs normal global one-to-one association against
     * the current canonical tracks. Any measurements that remain unassociated
     * are held here temporarily so later sensors at the same timestamp get a
     * chance to explain them before new canonical hypotheses are created.
     */
    private record PendingBirthBatch(
            List<SimpleMatrix> measurements,
            MeasurementModel sensorModel,
            String sensorId,
            long observationMs,
            Set<String> unavailableTrackIds
    ) {}

    private record ProvisionalBirth(
            String provisionalId,
            SimpleMatrix measurement,
            MeasurementModel sensorModel,
            String sensorId,
            long observationMs,
            ExtendedKalmanFilter filter
    ) {}

    private final List<PendingBirthBatch> pendingBirthBatches =
            new ArrayList<>();

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

        // 1. Use the frozen predicted prior shared by every sensor at this timestamp.
        sensorsInActiveCycle.add(sensorId);
        List<Candidate> candidates = associationCandidates;

        // 2. Associate
        Map<Integer, AssociationResult> results =
                associator.associateBatch(measurements, sensorModel, candidates);


        List<SimpleMatrix> unassociatedMeasurements =
                new ArrayList<>();

        Set<String> unavailableTrackIds =
                new HashSet<>();

        for (var entry : results.entrySet()) {
            int obsIdx = entry.getKey();
            AssociationResult result = entry.getValue();

            if (result instanceof AssociationResult.Associated a) {
                // Update the associated track immediately.
                Track track = tracks.get(a.trackId());
                if (track != null) {
                    track.update(
                            measurements.get(obsIdx),
                            sensorModel,
                            sensorId,
                            observationMs
                    );

                    updatedInActiveCycle.add(a.trackId());
                    unavailableTrackIds.add(a.trackId());
                }
            } else {
                /*
                 * Do NOT create a canonical track yet.
                 *
                 * More sensors may still report this same physical target at
                 * this same observation timestamp. Defer birth until the
                 * timestamp closes, then reconcile these measurements against
                 * all tracks created or updated during the full epoch.
                 */
                unassociatedMeasurements.add(
                        measurements.get(obsIdx)
                );
            }
        }

        if (!unassociatedMeasurements.isEmpty()) {
            pendingBirthBatches.add(
                    new PendingBirthBatch(
                            List.copyOf(unassociatedMeasurements),
                            sensorModel,
                            sensorId,
                            observationMs,
                            Set.copyOf(unavailableTrackIds)
                    )
            );
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
        sensorsInActiveCycle.clear();

        for (Track track : tracks.values()) {
            if (track.isAlive()) {
                track.predictTo(observationMs);
            }
        }

        associationCandidates = snapshotAliveCandidates();
    }

    /**
     * Capture immutable EKF snapshots for association at the active timestamp.
     * Later same-timestamp sensor updates modify only canonical tracks, never
     * these association priors.
     */
    private List<Candidate> snapshotAliveCandidates() {
        List<Candidate> snapshots = new ArrayList<>();

        for (Track track : tracks.values()) {
            if (track.isAlive()) {
                snapshots.add(snapshotCandidate(track));
            }
        }

        return List.copyOf(snapshots);
    }

    private Candidate snapshotCandidate(Track track) {
        ExtendedKalmanFilter source = track.getEkf();

        ExtendedKalmanFilter snapshot =
                new ExtendedKalmanFilter(
                        source.getState(),
                        source.getCovariance(),
                        motionModel
                );

        return new Candidate(
                track.getTrackId(),
                snapshot
        );
    }

    /**
     * Apply at most one lifecycle miss to each alive track for the completed
     * observation timestamp.
     */
    private void finalizeActiveObservationCycle() {
        /*
         * Resolve deferred births before lifecycle misses.
         *
         * This aligns track creation with the same observation-cycle boundary
         * already used for miss accounting. It prevents one sensor from
         * creating a duplicate canonical hypothesis before the other sensors
         * at the same timestamp have been considered.
         */
        resolvePendingBirthBatches();

        for (Track track : tracks.values()) {
            if (track.isAlive()
                    && !updatedInActiveCycle.contains(track.getTrackId())) {

                track.recordMiss(activeObservationMs);
            }
        }

        updatedInActiveCycle.clear();
    }

    /**
     * Reconcile measurements that were unassociated during their original
     * sensor batch after all same-timestamp sensor batches have arrived.
     *
     * Batches are resolved in deterministic arrival order. Within each batch,
     * DataAssociator still performs a global one-to-one assignment. Tracks
     * that were already consumed by another observation from the same original
     * sensor batch are excluded so same-sensor one-to-one semantics are
     * preserved.
     */
    private void resolvePendingBirthBatches() {
        if (pendingBirthBatches.isEmpty()) {
            return;
        }

        /*
         * New hypotheses created while closing this timestamp get their own
         * frozen association snapshots. This lets later sensors at the same
         * timestamp associate to those newborn tracks without being gated
         * against posteriors already modified by earlier sensors.
         */
        List<Candidate> birthAssociationCandidates =
                new ArrayList<>();

        List<ProvisionalBirth> provisionalBirths =
                new ArrayList<>();
        long provisionalCounter = 0L;

        for (PendingBirthBatch pending : pendingBirthBatches) {
            List<Candidate> candidates =
                    new ArrayList<>();

            for (Candidate candidate : associationCandidates) {
                Track canonical =
                        tracks.get(candidate.trackId());

                if (canonical != null
                        && canonical.isAlive()
                        && !pending.unavailableTrackIds()
                                .contains(candidate.trackId())) {

                    candidates.add(candidate);
                }
            }

            for (Candidate candidate : birthAssociationCandidates) {
                Track canonical =
                        tracks.get(candidate.trackId());

                if (canonical != null
                        && canonical.isAlive()
                        && !pending.unavailableTrackIds()
                                .contains(candidate.trackId())) {

                    candidates.add(candidate);
                }
            }

            Map<Integer, AssociationResult> results =
                    associator.associateBatch(
                            pending.measurements(),
                            pending.sensorModel(),
                            candidates
                    );

            for (var entry : results.entrySet()) {
                int obsIdx = entry.getKey();
                AssociationResult result = entry.getValue();

                if (result instanceof AssociationResult.Associated a) {
                    Track track =
                            tracks.get(a.trackId());

                    if (track != null && track.isAlive()) {
                        track.update(
                                pending.measurements().get(obsIdx),
                                pending.sensorModel(),
                                pending.sensorId(),
                                pending.observationMs()
                        );

                        updatedInActiveCycle.add(
                                a.trackId()
                        );
                    }

                } else {
                    SimpleMatrix measurement =
                            pending.measurements().get(obsIdx);

                    if (sensorsInActiveCycle.size() <= 1) {
                        String newTrackId =
                                initializeTrack(
                                        measurement,
                                        pending.sensorModel(),
                                        pending.sensorId(),
                                        pending.observationMs()
                                );

                        updatedInActiveCycle.add(newTrackId);

                        Track newTrack = tracks.get(newTrackId);
                        if (newTrack != null) {
                            birthAssociationCandidates.add(
                                    snapshotCandidate(newTrack)
                            );
                        }
                        continue;
                    }

                    List<Candidate> provisionalCandidates =
                            new ArrayList<>();

                    for (ProvisionalBirth provisional : provisionalBirths) {
                        if (!provisional.sensorId().equals(pending.sensorId())) {
                            provisionalCandidates.add(
                                    new Candidate(
                                            provisional.provisionalId(),
                                            provisional.filter()
                                    )
                            );
                        }
                    }

                    AssociationResult provisionalMatch =
                            associator.associate(
                                    measurement,
                                    pending.sensorModel(),
                                    provisionalCandidates
                            );

                    if (provisionalMatch instanceof AssociationResult.Associated a) {
                        ProvisionalBirth seed =
                                provisionalBirths.stream()
                                        .filter(p -> p.provisionalId().equals(a.trackId()))
                                        .findFirst()
                                        .orElseThrow();

                        String newTrackId =
                                initializeTrack(
                                        seed.measurement(),
                                        seed.sensorModel(),
                                        seed.sensorId(),
                                        seed.observationMs()
                                );

                        Track newTrack = tracks.get(newTrackId);
                        if (newTrack != null) {
                            newTrack.update(
                                    measurement,
                                    pending.sensorModel(),
                                    pending.sensorId(),
                                    pending.observationMs()
                            );
                            updatedInActiveCycle.add(newTrackId);
                            birthAssociationCandidates.add(snapshotCandidate(newTrack));
                        }

                        provisionalBirths.remove(seed);
                    } else {
                        String provisionalId =
                                "BIRTH-%06d".formatted(++provisionalCounter);

                        provisionalBirths.add(
                                new ProvisionalBirth(
                                        provisionalId,
                                        measurement,
                                        pending.sensorModel(),
                                        pending.sensorId(),
                                        pending.observationMs(),
                                        createInitialFilter(
                                                measurement,
                                                pending.sensorModel()
                                        )
                                )
                        );
                    }
                }
            }
        }

        pendingBirthBatches.clear();
    }

    private ExtendedKalmanFilter createInitialFilter(
            SimpleMatrix measurement,
            MeasurementModel sensorModel) {

        double range = measurement.get(0);
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

        P0.set(0, 0, varX + initialPositionVar);
        P0.set(1, 1, varY + initialPositionVar);
        P0.set(0, 1, covXY);
        P0.set(1, 0, covXY);
        P0.set(2, 2, initialVelocityVar);
        P0.set(3, 3, initialVelocityVar);

        return new ExtendedKalmanFilter(
                x0,
                P0,
                motionModel
        );
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

    /**
     * Coalesce duplicate hypotheses after all sensors for one observation
     * timestamp have been processed.
     *
     * A newly spawned TENTATIVE hypothesis can be close to an already stable
     * track when one sensor briefly fails the statistical gate. Confirmed /
     * coasting tracks are merged only when both position and velocity agree,
     * which keeps opposing crossing targets distinct.
     *
     * @return number of hypotheses marked DROPPED
     */
    public int suppressDuplicateTracks() {
        List<Track> alive =
                new ArrayList<>(
                        getAliveTracks()
                );

        Set<String> droppedIds =
                new HashSet<>();

        for (int i = 0; i < alive.size(); i++) {
            Track first =
                    alive.get(i);

            if (!first.isAlive()
                    || droppedIds.contains(
                            first.getTrackId()
                    )) {
                continue;
            }

            for (int j = i + 1;
                 j < alive.size();
                 j++) {

                Track second =
                        alive.get(j);

                if (!second.isAlive()
                        || droppedIds.contains(
                                second.getTrackId()
                        )) {
                    continue;
                }

                if (!isLikelyDuplicate(
                        first,
                        second
                )) {
                    continue;
                }

                Track survivor =
                        chooseDuplicateSurvivor(
                                first,
                                second
                        );

                Track duplicate =
                        survivor == first
                                ? second
                                : first;

                survivor.absorbSensorSources(
                        duplicate
                );

                duplicate.markDropped();

                droppedIds.add(
                        duplicate.getTrackId()
                );

                if (duplicate == first) {
                    break;
                }
            }
        }

        return droppedIds.size();
    }

    private boolean isLikelyDuplicate(
            Track first,
            Track second) {

        double dx =
                first.getPx() -
                        second.getPx();

        double dy =
                first.getPy() -
                        second.getPy();

        double positionDistance =
                Math.hypot(
                        dx,
                        dy
                );

        boolean eitherTentative =
                first.getState() == TrackState.TENTATIVE
                        || second.getState() == TrackState.TENTATIVE;

        if (eitherTentative) {
            if (positionDistance
                    > TENTATIVE_DUPLICATE_POSITION_M) {
                return false;
            }

            boolean sharedSensor =
                    !Collections.disjoint(
                            first.getContributingSensors(),
                            second.getContributingSensors()
                    );

            return positionDistance <= 180.0
                    || sharedSensor;
        }

        if (positionDistance
                > CONFIRMED_DUPLICATE_POSITION_M) {
            return false;
        }

        double dvx =
                first.getVx() -
                        second.getVx();

        double dvy =
                first.getVy() -
                        second.getVy();

        double velocityDistance =
                Math.hypot(
                        dvx,
                        dvy
                );

        return velocityDistance
                <= CONFIRMED_DUPLICATE_VELOCITY_MPS;
    }

    private Track chooseDuplicateSurvivor(
            Track first,
            Track second) {

        int firstStateScore =
                stateQuality(
                        first.getState()
                );

        int secondStateScore =
                stateQuality(
                        second.getState()
                );

        if (firstStateScore
                != secondStateScore) {

            return firstStateScore
                    > secondStateScore
                    ? first
                    : second;
        }

        if (first.getTotalHits()
                != second.getTotalHits()) {

            return first.getTotalHits()
                    > second.getTotalHits()
                    ? first
                    : second;
        }

        int firstSensors =
                first.getContributingSensors()
                        .size();

        int secondSensors =
                second.getContributingSensors()
                        .size();

        if (firstSensors != secondSensors) {
            return firstSensors > secondSensors
                    ? first
                    : second;
        }

        double firstUncertainty =
                first.getPositionUncertainty();

        double secondUncertainty =
                second.getPositionUncertainty();

        if (Double.compare(
                firstUncertainty,
                secondUncertainty
        ) != 0) {

            return firstUncertainty
                    < secondUncertainty
                    ? first
                    : second;
        }

        return first.getTrackId()
                        .compareTo(
                                second.getTrackId()
                        ) <= 0
                ? first
                : second;
    }

    private int stateQuality(
            TrackState state) {

        return switch (state) {
            case CONFIRMED -> 3;
            case COASTING -> 2;
            case TENTATIVE -> 1;
            case DROPPED -> 0;
        };
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
