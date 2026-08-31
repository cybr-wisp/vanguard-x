package com.vanguard.simulator;

import java.util.*;

/**
 * The world simulator owns ground-truth trajectories. Given the same seed and
 * scenario config, output is fully deterministic.
 *
 * Ground-truth target IDs are never exposed to sensor nodes or the tracking
 * pipeline. They are retained internally for evaluation scoring only.
 */
public class WorldSimulator {

    private final long seed;
    private final List<TargetModel> targets;
    private final long scenarioStartMs;
    private final long scenarioEndMs;

    private WorldSimulator(long seed, List<TargetModel> targets) {
        this.seed = seed;
        this.targets = List.copyOf(targets);
        this.scenarioStartMs = targets.stream().mapToLong(TargetModel::getStartMs).min().orElse(0);
        this.scenarioEndMs   = targets.stream().mapToLong(TargetModel::getEndMs).max().orElse(0);
    }

    public long getSeed()           { return seed; }
    public int  getTargetCount()    { return targets.size(); }
    public long getScenarioStartMs(){ return scenarioStartMs; }
    public long getScenarioEndMs()  { return scenarioEndMs; }
    public List<TargetModel> getTargets() { return targets; }

    /** Ground-truth state of every active target at time t. */
    public List<TargetModel.TruthRecord> truthAt(long tMs) {
        List<TargetModel.TruthRecord> out = new ArrayList<>();
        for (TargetModel target : targets) {
            TargetModel.TruthRecord r = target.stateAt(tMs);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Full trajectory sample, sorted by timestamp then target ID. */
    public List<TargetModel.TruthRecord> generateFullTrajectories(long sampleIntervalMs) {
        List<TargetModel.TruthRecord> all = new ArrayList<>();
        for (TargetModel t : targets) {
            all.addAll(t.sampleTrajectory(sampleIntervalMs));
        }
        all.sort(Comparator.comparingLong(TargetModel.TruthRecord::timestampMs)
                .thenComparing(TargetModel.TruthRecord::targetId));
        return all;
    }

    /** Look up a target by hidden ID (evaluation harness only). */
    public Optional<TargetModel> findTarget(String targetId) {
        return targets.stream().filter(t -> t.getTargetId().equals(targetId)).findFirst();
    }

    // ---- Factory ----

    public static WorldSimulator fromConfig(ScenarioConfig config) {
        List<TargetModel> models = new ArrayList<>();
        for (ScenarioConfig.TargetSpec spec : config.targets()) {
            models.add(buildTarget(spec));
        }
        return new WorldSimulator(config.seed(), models);
    }

    private static TargetModel buildTarget(ScenarioConfig.TargetSpec spec) {
        TargetModel.Builder b = TargetModel.builder(spec.id())
                .initialState(spec.startMs(), spec.px0(), spec.py0(), spec.vx0(), spec.vy0());
        for (ScenarioConfig.SegmentSpec seg : spec.segments()) {
            switch (seg.type()) {
                case STRAIGHT   -> b.straight(seg.durationSec());
                case ACCELERATE -> b.accelerate(seg.durationSec(), seg.ax(), seg.ay());
                case TURN       -> b.turn(seg.durationSec(), seg.omegaRadPerSec());
            }
        }
        return b.build();
    }
}
