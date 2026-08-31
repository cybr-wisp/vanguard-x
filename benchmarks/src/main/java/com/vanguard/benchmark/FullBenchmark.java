package com.vanguard.benchmark;

import com.vanguard.simulator.*;
import com.vanguard.tracking.association.*;
import com.vanguard.tracking.estimation.*;
import com.vanguard.tracking.evaluation.*;
import com.vanguard.tracking.lifecycle.*;
import com.vanguard.spatial.*;
import org.ejml.simple.SimpleMatrix;

import java.util.*;
import java.util.concurrent.*;

public class FullBenchmark {

    static final MotionModel MOTION = new MotionModel(2.0);

    public static void main(String[] args) {
        System.out.println("=== VANGUARD v1.0 BENCHMARK ===");
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println("Cores: " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        // Core metrics
        runAccuracy();
        runFusion();
        runPacketLoss();

        // Performance: BEFORE and AFTER spatial index
        System.out.println("--- THROUGHPUT (before spatial index) ---");
        runThroughput(false);
        System.out.println("--- THROUGHPUT (after spatial index) ---");
        runThroughput(true);

        System.out.println("--- LATENCY (before spatial index) ---");
        runLatency(false);
        System.out.println("--- LATENCY (after spatial index) ---");
        runLatency(true);

        // Elite metrics
        runCovarianceHonesty();
        runEventDedup();
        runReplayDeterminism();
        runExecutors();

        System.out.println("\n=== DONE ===");
    }

    // 1. Accuracy
    static void runAccuracy() {
        System.out.println("--- 1. TRACKING ACCURACY ---");
        System.out.print("  Running...");
        ScenarioConfig config = ScenarioLoader.minimalScenario();
        WorldSimulator world = WorldSimulator.fromConfig(config);
        DataAssociator assoc = new DataAssociator(new MahalanobisGate(9.21));
        TrackManager mgr = new TrackManager(assoc, MOTION, 3, 3, 8, 10000, 100);
        TrackingEvaluator eval = new TrackingEvaluator();
        Random rng = new Random(config.seed());
        SensorNode sensor = new SensorNode(config.sensors().getFirst(), new Random(rng.nextLong()));
        MeasurementModel mm = makeMM(config.sensors().getFirst());

        for (long t = 100; t <= config.scenarioDurationMs(); t += 100) {
            List<TargetModel.TruthRecord> truth = world.truthAt(t);
            List<SensorNode.RawReport> reports = sensor.observe(truth, t, rng);
            List<SimpleMatrix> meas = new ArrayList<>(); List<String> tids = new ArrayList<>();
            for (SensorNode.RawReport r : reports) { if (r.isFalseDetection()) continue; meas.add(toZ(r)); tids.add(r.hiddenTruthId()); }
            var results = mgr.processObservations(meas, mm, sensor.getSensorId(), t);
            recordEval(results, tids, truth, mgr, eval);
        }
        var r = eval.evaluate();
        System.out.println(" done");
        System.out.printf("  Position RMSE:       %.2f m%n", r.positionRmse());
        System.out.printf("  Velocity RMSE:       %.2f m/s%n", r.velocityRmse());
        System.out.printf("  Association acc:     %.1f%%%n", r.associationAccuracy() * 100);
        System.out.printf("  Fragmentation:       %d%n", r.trackFragmentation());
        System.out.printf("  False tracks:        %d%n", r.falseTracks());
        System.out.println();
    }

    // 2. Fusion vs Raw
    static void runFusion() {
        System.out.println("--- 2. FUSION vs RAW ---");
        ScenarioConfig config = ScenarioLoader.minimalScenario();
        WorldSimulator world = WorldSimulator.fromConfig(config);
        Random rng = new Random(config.seed());
        SensorNode sensor = new SensorNode(config.sensors().getFirst(), new Random(rng.nextLong()));
        MeasurementModel mm = makeMM(config.sensors().getFirst());
        DataAssociator assoc = new DataAssociator(new MahalanobisGate(9.21));
        TrackManager mgr = new TrackManager(assoc, MOTION, 3, 3, 8, 10000, 100);
        double sumRaw2 = 0, sumFused2 = 0; int count = 0;

        for (long t = 100; t <= config.scenarioDurationMs(); t += 100) {
            List<TargetModel.TruthRecord> truth = world.truthAt(t);
            List<SensorNode.RawReport> reports = sensor.observe(truth, t, rng);
            List<SimpleMatrix> meas = new ArrayList<>(); List<String> tids = new ArrayList<>();
            for (SensorNode.RawReport r : reports) {
                if (r.isFalseDetection()) continue;
                meas.add(toZ(r)); tids.add(r.hiddenTruthId());
                double rawPx = r.sensorX() + r.rangeM() * Math.cos(r.azimuthRad());
                double rawPy = r.sensorY() + r.rangeM() * Math.sin(r.azimuthRad());
                TargetModel.TruthRecord tr = findTruth(truth, r.hiddenTruthId());
                if (tr != null) { sumRaw2 += sq(rawPx - tr.px()) + sq(rawPy - tr.py()); count++; }
            }
            var results = mgr.processObservations(meas, mm, sensor.getSensorId(), t);
            for (var e : results.entrySet()) {
                if (e.getValue() instanceof DataAssociator.AssociationResult.Associated a) {
                    Track track = mgr.getTrack(a.trackId()).orElse(null);
                    TargetModel.TruthRecord tr = findTruth(truth, tids.get(e.getKey()));
                    if (track != null && tr != null) sumFused2 += sq(track.getPx() - tr.px()) + sq(track.getPy() - tr.py());
                }
            }
        }
        double rawR = Math.sqrt(sumRaw2 / count), fusR = Math.sqrt(sumFused2 / Math.max(1, count));
        System.out.printf("  Raw RMSE:      %.2f m%n  Fused RMSE:    %.2f m%n  Improvement:   %.1f%%%n%n", rawR, fusR, (1 - fusR / rawR) * 100);
    }

    // 3. Packet loss with reacquisition tracking
    static void runPacketLoss() {
        System.out.println("--- 3. PACKET LOSS + REACQUISITION ---");
        double[] rates = {0.0, 0.05, 0.10, 0.20};
        for (double loss : rates) {
            ScenarioConfig config = ScenarioLoader.minimalScenario();
            WorldSimulator world = WorldSimulator.fromConfig(config);
            DataAssociator assoc = new DataAssociator(new MahalanobisGate(9.21));
            TrackManager mgr = new TrackManager(assoc, MOTION, 3, 3, 8, 10000, 100);
            TrackingEvaluator eval = new TrackingEvaluator();
            Random rng = new Random(config.seed());
            SensorNode sensor = new SensorNode(config.sensors().getFirst(), new Random(rng.nextLong()));
            MeasurementModel mm = makeMM(config.sensors().getFirst());
            NetworkImpairmentModel imp = new NetworkImpairmentModel(
                    new ScenarioConfig.ImpairmentSpec(loss, 0, 0, 0, loss > 0), new Random(99));
            int maxCoast = 0, reacquisitions = 0, dupTracks = 0;
            Map<String, String> trackTruthMap = new HashMap<>();

            for (long t = 100; t <= config.scenarioDurationMs(); t += 100) {
                List<TargetModel.TruthRecord> truth = world.truthAt(t);
                List<SensorNode.RawReport> raw = sensor.observe(truth, t, rng);
                List<SensorNode.RawReport> surviving = imp.apply(raw);
                List<SimpleMatrix> meas = new ArrayList<>(); List<String> tids = new ArrayList<>();
                for (SensorNode.RawReport r : surviving) { if (r.isFalseDetection()) continue; meas.add(toZ(r)); tids.add(r.hiddenTruthId()); }

                // Track states before processing
                Map<String, TrackState> statesBefore = new HashMap<>();
                mgr.getAliveTracks().forEach(tr -> statesBefore.put(tr.getTrackId(), tr.getState()));

                var results = mgr.processObservations(meas, mm, sensor.getSensorId(), t);

                // Detect reacquisitions (COASTING -> CONFIRMED)
                for (Track tr : mgr.getAliveTracks()) {
                    TrackState before = statesBefore.get(tr.getTrackId());
                    if (before == TrackState.COASTING && tr.getState() == TrackState.CONFIRMED)
                        reacquisitions++;
                }

                // Track truth assignments for dup detection
                for (var e : results.entrySet()) {
                    if (e.getValue() instanceof DataAssociator.AssociationResult.Associated a) {
                        String tid = tids.get(e.getKey());
                        String existing = trackTruthMap.get(tid);
                        if (existing != null && !existing.equals(a.trackId())) dupTracks++;
                        trackTruthMap.putIfAbsent(tid, a.trackId());
                        eval.recordAssociation(a.trackId(), tid);
                        Track track = mgr.getTrack(a.trackId()).orElse(null);
                        TargetModel.TruthRecord tr = findTruth(truth, tid);
                        if (track != null && tr != null)
                            eval.record(a.trackId(), tid, t, track.getPx(), track.getPy(), track.getVx(), track.getVy(), tr.px(), tr.py(), tr.vx(), tr.vy());
                    }
                }
                maxCoast = Math.max(maxCoast, mgr.getCoastingCount());
            }
            var r = eval.evaluate();
            System.out.printf("  %3.0f%% loss: RMSE=%.2fm  acc=%.1f%%  coast=%d  reacq=%d  dups=%d  frag=%d%n",
                    loss * 100, r.positionRmse(), r.associationAccuracy() * 100, maxCoast, reacquisitions, dupTracks, r.trackFragmentation());
        }
        System.out.println();
    }

    // 4. Throughput (with/without spatial index)
    static void runThroughput(boolean useSpatialIndex) {
        int[] targets = {50, 200, 500, 1000};
        int warmup = 100, measured = 500;
        for (int n : targets) {
            DataAssociator assoc = useSpatialIndex
                    ? new DataAssociator(new MahalanobisGate(9.21), 300.0)
                    : new DataAssociator(new MahalanobisGate(9.21));
            TrackManager mgr = new TrackManager(assoc, MOTION, 3, 3, 8, 10000, 100);
            MeasurementModel mm = new MeasurementModel(0, 0, 50, 0.01);
            Random rng = new Random(42);
            for (int i = 0; i < warmup; i++) mgr.processObservations(genBatch(n, rng), mm, "S1", i * 100L);
            long t0 = System.nanoTime(); int total = 0;
            for (int i = 0; i < measured; i++) {
                List<SimpleMatrix> batch = genBatch(n, rng);
                mgr.processObservations(batch, mm, "S1", (warmup + i) * 100L);
                total += batch.size();
            }
            double sec = (System.nanoTime() - t0) / 1e9;
            System.out.printf("  %4d targets: %,10.0f reports/sec (%.1fs)%n", n, total / sec, sec);
        }
        System.out.println();
    }

    // 5. Latency (with/without spatial index)
    static void runLatency(boolean useSpatialIndex) {
        int n = 200, warmup = 200, measured = 2000;
        DataAssociator assoc = useSpatialIndex
                ? new DataAssociator(new MahalanobisGate(9.21), 300.0)
                : new DataAssociator(new MahalanobisGate(9.21));
        TrackManager mgr = new TrackManager(assoc, MOTION, 3, 3, 8, 10000, 100);
        MeasurementModel mm = new MeasurementModel(0, 0, 50, 0.01);
        Random rng = new Random(42);
        for (int i = 0; i < warmup; i++) mgr.processObservations(genBatch(n, rng), mm, "S1", i * 100L);
        long[] lat = new long[measured];
        for (int i = 0; i < measured; i++) {
            List<SimpleMatrix> batch = genBatch(n, rng);
            long t0 = System.nanoTime();
            mgr.processObservations(batch, mm, "S1", (warmup + i) * 100L);
            lat[i] = System.nanoTime() - t0;
        }
        Arrays.sort(lat);
        System.out.printf("  200 targets: p50=%.2fms  p95=%.2fms  p99=%.2fms  max=%.2fms%n",
                lat[(int)(measured * 0.50)] / 1e6, lat[(int)(measured * 0.95)] / 1e6,
                lat[(int)(measured * 0.99)] / 1e6, lat[measured - 1] / 1e6);
        System.out.println();
    }

    // 6. Covariance honesty
    static void runCovarianceHonesty() {
        System.out.println("--- COVARIANCE HONESTY ---");
        MeasurementModel mm = new MeasurementModel(0, 0, 50, 0.01);
        SimpleMatrix x0 = new SimpleMatrix(new double[][]{{500}, {500}, {10}, {5}});
        SimpleMatrix P0 = SimpleMatrix.identity(4).scale(1000);
        ExtendedKalmanFilter ekf = new ExtendedKalmanFilter(x0, P0, MOTION);

        double uncAfterInit = ekf.getPositionUncertainty();
        System.out.printf("  After init:           %.2f m%n", uncAfterInit);

        // Update with a good observation -> uncertainty should shrink
        SimpleMatrix z = mm.h(x0);
        ekf.update(z, mm);
        double uncAfterUpdate = ekf.getPositionUncertainty();
        System.out.printf("  After 1st update:     %.2f m (%.0f%% reduction)%n",
                uncAfterUpdate, (1 - uncAfterUpdate / uncAfterInit) * 100);

        // 5 more updates
        for (int i = 0; i < 5; i++) { ekf.predict(0.1); ekf.update(mm.h(ekf.getState()), mm); }
        double uncAfter5 = ekf.getPositionUncertainty();
        System.out.printf("  After 6 updates:      %.2f m%n", uncAfter5);

        // Now coast for 10 cycles, measure growth rate
        double uncBeforeCoast = ekf.getPositionUncertainty();
        double[] coastUnc = new double[10];
        for (int i = 0; i < 10; i++) { ekf.predict(1.0); coastUnc[i] = ekf.getPositionUncertainty(); }
        System.out.printf("  Before coasting:      %.2f m%n", uncBeforeCoast);
        System.out.printf("  After 10 coast cycles: %.2f m%n", coastUnc[9]);
        double avgGrowth = 0;
        double prev = uncBeforeCoast;
        for (int i = 0; i < 10; i++) { avgGrowth += (coastUnc[i] - prev) / prev; prev = coastUnc[i]; }
        avgGrowth /= 10;
        System.out.printf("  Avg growth per cycle: %.1f%%%n", avgGrowth * 100);

        // Reacquire -> uncertainty should drop
        ekf.update(mm.h(ekf.getState()), mm);
        double uncAfterReacq = ekf.getPositionUncertainty();
        System.out.printf("  After reacquisition:  %.2f m (%.0f%% drop)%n",
                uncAfterReacq, (1 - uncAfterReacq / coastUnc[9]) * 100);
        System.out.println();
    }

    // 7. Event deduplication
    static void runEventDedup() {
        System.out.println("--- EVENT DEDUPLICATION ---");
        AlertStateMachine sm = new AlertStateMachine();
        int eventCount = 0;
        for (int i = 0; i < 1000; i++) {
            var e = sm.update("T1", "Z1", ZoneClassification.BREACH, i * 100, 50, 50);
            if (e.isPresent()) eventCount++;
        }
        System.out.printf("  1000 repeated BREACH inputs -> %d event(s) emitted%n", eventCount);
        // Exit and re-enter
        sm.update("T1", "Z1", ZoneClassification.CLEAR, 100000, 300, 300);
        var reentry = sm.update("T1", "Z1", ZoneClassification.BREACH, 100100, 50, 50);
        System.out.printf("  Exit + re-entry -> %s%n", reentry.isPresent() ? "new ZONE_ENTRY emitted" : "no event");
        System.out.println();
    }

    // 8. Replay determinism
    static void runReplayDeterminism() {
        System.out.println("--- REPLAY DETERMINISM ---");
        double rmse1 = runScenarioForRmse(ScenarioLoader.minimalScenario());
        double rmse2 = runScenarioForRmse(ScenarioLoader.minimalScenario());
        double rmse3 = runScenarioForRmse(ScenarioLoader.minimalScenario());
        System.out.printf("  Run 1 RMSE: %.6f m%n", rmse1);
        System.out.printf("  Run 2 RMSE: %.6f m%n", rmse2);
        System.out.printf("  Run 3 RMSE: %.6f m%n", rmse3);
        System.out.printf("  Max delta:  %.2e m%n", Math.max(Math.abs(rmse1 - rmse2), Math.abs(rmse2 - rmse3)));
        System.out.printf("  Identical:  %s%n", rmse1 == rmse2 && rmse2 == rmse3 ? "YES" : "NO");
        System.out.println();
    }

    static double runScenarioForRmse(ScenarioConfig config) {
        WorldSimulator world = WorldSimulator.fromConfig(config);
        DataAssociator assoc = new DataAssociator(new MahalanobisGate(9.21));
        TrackManager mgr = new TrackManager(assoc, MOTION, 3, 3, 8, 10000, 100);
        TrackingEvaluator eval = new TrackingEvaluator();
        Random rng = new Random(config.seed());
        SensorNode sensor = new SensorNode(config.sensors().getFirst(), new Random(rng.nextLong()));
        MeasurementModel mm = makeMM(config.sensors().getFirst());
        for (long t = 100; t <= config.scenarioDurationMs(); t += 100) {
            List<TargetModel.TruthRecord> truth = world.truthAt(t);
            List<SensorNode.RawReport> reports = sensor.observe(truth, t, rng);
            List<SimpleMatrix> meas = new ArrayList<>(); List<String> tids = new ArrayList<>();
            for (SensorNode.RawReport r : reports) { if (r.isFalseDetection()) continue; meas.add(toZ(r)); tids.add(r.hiddenTruthId()); }
            var results = mgr.processObservations(meas, mm, sensor.getSensorId(), t);
            recordEval(results, tids, truth, mgr, eval);
        }
        return eval.evaluate().positionRmse();
    }

    // 9. Executor comparison
    static void runExecutors() {
        System.out.println("--- EXECUTOR COMPARISON ---");
        int iters = 1000, warmup = 200;
        MeasurementModel mm = new MeasurementModel(0, 0, 50, 0.01);
        long[] fixedLat = runWorkload(iters, warmup, mm);
        ExecutorService vtExec = Executors.newVirtualThreadPerTaskExecutor();
        long[] vtLat;
        try { vtLat = vtExec.submit(() -> runWorkload(iters, warmup, mm)).get(); } catch (Exception e) { vtLat = fixedLat; }
        vtExec.shutdown();
        Arrays.sort(fixedLat); Arrays.sort(vtLat);
        System.out.printf("  %-16s %10s %10s%n", "", "Fixed", "Virtual");
        System.out.printf("  %-16s %10.2f %10.2f%n", "p50 (ms)", fixedLat[(int)(iters * 0.50)] / 1e6, vtLat[(int)(iters * 0.50)] / 1e6);
        System.out.printf("  %-16s %10.2f %10.2f%n", "p95 (ms)", fixedLat[(int)(iters * 0.95)] / 1e6, vtLat[(int)(iters * 0.95)] / 1e6);
        System.out.printf("  %-16s %10.2f %10.2f%n", "p99 (ms)", fixedLat[(int)(iters * 0.99)] / 1e6, vtLat[(int)(iters * 0.99)] / 1e6);
    }

    // --- Helpers ---
    static long[] runWorkload(int iters, int warmup, MeasurementModel mm) {
        DataAssociator assoc = new DataAssociator(new MahalanobisGate(9.21));
        TrackManager mgr = new TrackManager(assoc, MOTION, 3, 3, 8, 10000, 100);
        Random rng = new Random(42);
        for (int i = 0; i < warmup; i++) mgr.processObservations(genBatch(100, rng), mm, "S1", i * 100L);
        long[] lat = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            mgr.processObservations(genBatch(100, rng), mm, "S1", (warmup + i) * 100L);
            lat[i] = System.nanoTime() - t0;
        }
        return lat;
    }
    static List<SimpleMatrix> genBatch(int n, Random rng) {
        List<SimpleMatrix> b = new ArrayList<>(n);
        for (int i = 0; i < n; i++) b.add(new SimpleMatrix(new double[][]{{500 + rng.nextDouble() * 5000}, {rng.nextDouble() * 2 * Math.PI - Math.PI}}));
        return b;
    }
    static SimpleMatrix toZ(SensorNode.RawReport r) { return new SimpleMatrix(new double[][]{{r.rangeM()}, {r.azimuthRad()}}); }
    static MeasurementModel makeMM(ScenarioConfig.SensorSpec s) { return new MeasurementModel(s.sx(), s.sy(), s.sigmaRangeM(), s.sigmaBearingRad()); }
    static TargetModel.TruthRecord findTruth(List<TargetModel.TruthRecord> truth, String id) { return truth.stream().filter(x -> x.targetId().equals(id)).findFirst().orElse(null); }
    static double sq(double x) { return x * x; }
    static void recordEval(Map<Integer, DataAssociator.AssociationResult> results, List<String> tids, List<TargetModel.TruthRecord> truth, TrackManager mgr, TrackingEvaluator eval) {
        for (var e : results.entrySet()) {
            if (e.getValue() instanceof DataAssociator.AssociationResult.Associated a) {
                String tid = tids.get(e.getKey()); eval.recordAssociation(a.trackId(), tid);
                Track track = mgr.getTrack(a.trackId()).orElse(null); TargetModel.TruthRecord tr = findTruth(truth, tid);
                if (track != null && tr != null) eval.record(a.trackId(), tid, 0, track.getPx(), track.getPy(), track.getVx(), track.getVy(), tr.px(), tr.py(), tr.vx(), tr.vy());
            }
        }
    }
}
