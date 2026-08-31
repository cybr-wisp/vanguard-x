package com.vanguard.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SensorAndImpairmentTest {

    // ---- SensorNode tests ----

    @Test
    @DisplayName("Sensor produces noisy range/bearing, not raw x/y")
    void sensorProducesRangeBearing() {
        var spec = new ScenarioConfig.SensorSpec(
                "S1", 0, 0, 30, 0.01, 0, 0, 100, 0);
        SensorNode sensor = new SensorNode(spec, new Random(1));

        // Target at (300, 400) -> true range = 500, true bearing = atan2(400,300)
        var truth = List.of(new TargetModel.TruthRecord("T1", 0, 300, 400, 10, 0));
        List<SensorNode.RawReport> reports = sensor.observe(truth, 0, new Random(1));

        assertEquals(1, reports.size());
        SensorNode.RawReport r = reports.getFirst();

        double trueRange = 500.0;
        double trueBearing = Math.atan2(400, 300);

        // Range and bearing should be close to truth but NOT exact (noise applied)
        assertEquals(trueRange, r.rangeM(), 200, "Range should be within noise band");
        assertEquals(trueBearing, r.azimuthRad(), 0.1, "Bearing should be within noise band");
        // But not exactly equal (noise was applied)
        // This test could theoretically fail with P ~ 0 for sigma=30m
        assertNotEquals(trueRange, r.rangeM(), "Range should have noise");
    }

    @Test
    @DisplayName("Same target looks different from different sensors")
    void sameTargetDifferentSensors() {
        var spec1 = new ScenarioConfig.SensorSpec("S1", -1000, 0, 50, 0.01, 0, 0, 100, 0);
        var spec2 = new ScenarioConfig.SensorSpec("S2",  1000, 0, 50, 0.01, 0, 0, 100, 0);

        SensorNode sensor1 = new SensorNode(spec1, new Random(10));
        SensorNode sensor2 = new SensorNode(spec2, new Random(20));

        var truth = List.of(new TargetModel.TruthRecord("T1", 0, 0, 500, 10, 0));

        SensorNode.RawReport r1 = sensor1.observe(truth, 0, new Random(10)).getFirst();
        SensorNode.RawReport r2 = sensor2.observe(truth, 0, new Random(20)).getFirst();

        // Different sensor positions -> different range and bearing
        assertNotEquals(r1.rangeM(), r2.rangeM(), 1e-6,
                "Different sensors should report different ranges");
        assertNotEquals(r1.azimuthRad(), r2.azimuthRad(), 1e-6,
                "Different sensors should report different bearings");
    }

    @Test
    @DisplayName("Sensor bias shifts measurements systematically")
    void sensorBiasShifts() {
        double biasRange = 100.0; // large bias for easy detection
        var spec = new ScenarioConfig.SensorSpec(
                "S-BIASED", 0, 0, 0.001, 0.0001, biasRange, 0, 100, 0);
        // Very small sigma so noise is negligible, bias dominates
        SensorNode sensor = new SensorNode(spec, new Random(42));

        var truth = List.of(new TargetModel.TruthRecord("T1", 0, 1000, 0, 0, 0));
        SensorNode.RawReport r = sensor.observe(truth, 0, new Random(42)).getFirst();

        // True range is 1000m, with +100m bias we expect ~1100m
        assertEquals(1100.0, r.rangeM(), 5.0, "Bias should shift range by ~100m");
    }

    @Test
    @DisplayName("False detections appear at configured rate")
    void falseDetections() {
        var spec = new ScenarioConfig.SensorSpec(
                "S1", 0, 0, 30, 0.01, 0, 0, 100, 1.0); // 100% false detection rate
        SensorNode sensor = new SensorNode(spec, new Random(1));

        var truth = List.of(new TargetModel.TruthRecord("T1", 0, 500, 500, 0, 0));

        // With 100% false detection rate, every observe should add a clutter report
        List<SensorNode.RawReport> reports = sensor.observe(truth, 0, new Random(1));
        long falseCount = reports.stream().filter(SensorNode.RawReport::isFalseDetection).count();
        assertEquals(1, falseCount, "Should have exactly one false detection with rate=1.0");
    }

    @Test
    @DisplayName("Bearing is normalized to [-pi, pi]")
    void bearingNormalization() {
        assertEquals(0.0, SensorNode.normalizeAngle(0), 1e-12);
        assertEquals(Math.PI, SensorNode.normalizeAngle(Math.PI), 1e-12);
        assertEquals(-Math.PI, SensorNode.normalizeAngle(-Math.PI), 1e-12);
        assertEquals(0.0, SensorNode.normalizeAngle(2 * Math.PI), 1e-12);
        assertEquals(0.5, SensorNode.normalizeAngle(0.5 + 4 * Math.PI), 1e-12);
        assertEquals(-0.5, SensorNode.normalizeAngle(-0.5 - 4 * Math.PI), 1e-12);
    }

    @Test
    @DisplayName("Sensor reports carry sensor position, not target position")
    void reportsCarrySensorPosition() {
        var spec = new ScenarioConfig.SensorSpec("S1", -500, 300, 30, 0.01, 0, 0, 100, 0);
        SensorNode sensor = new SensorNode(spec, new Random(1));

        var truth = List.of(new TargetModel.TruthRecord("T1", 0, 1000, 2000, 5, 0));
        SensorNode.RawReport r = sensor.observe(truth, 0, new Random(1)).getFirst();

        assertEquals(-500.0, r.sensorX(), 1e-12);
        assertEquals(300.0, r.sensorY(), 1e-12);
    }

    // ---- NetworkImpairmentModel tests ----

    @Test
    @DisplayName("No impairments: output equals input")
    void noImpairments() {
        var imp = new NetworkImpairmentModel(ScenarioConfig.ImpairmentSpec.none(), new Random(1));

        List<SensorNode.RawReport> input = makeReports(10, 0);
        List<SensorNode.RawReport> output = imp.apply(input);

        assertEquals(input.size(), output.size());
        for (int i = 0; i < input.size(); i++) {
            assertEquals(input.get(i).sequenceNumber(), output.get(i).sequenceNumber());
        }
    }

    @Test
    @DisplayName("100% packet loss drops everything")
    void fullPacketLoss() {
        var spec = new ScenarioConfig.ImpairmentSpec(1.0, 0, 0, 0, true);
        var imp = new NetworkImpairmentModel(spec, new Random(1));

        List<SensorNode.RawReport> output = imp.apply(makeReports(100, 0));
        assertEquals(0, output.size(), "100% loss should drop all reports");
    }

    @Test
    @DisplayName("Partial packet loss reduces output count")
    void partialPacketLoss() {
        var spec = new ScenarioConfig.ImpairmentSpec(0.5, 0, 0, 0, true);

        // Run many trials for statistical stability
        int totalInput = 10_000;
        int totalOutput = 0;
        Random rng = new Random(42);
        for (int i = 0; i < 100; i++) {
            var imp = new NetworkImpairmentModel(spec, rng);
            totalOutput += imp.apply(makeReports(totalInput / 100, i * 100)).size();
        }

        double observedLossRate = 1.0 - (double) totalOutput / totalInput;
        assertEquals(0.5, observedLossRate, 0.05,
                "Observed loss rate should be close to 50%");
    }

    @Test
    @DisplayName("Duplication increases output count")
    void duplicationIncreasesCount() {
        var spec = new ScenarioConfig.ImpairmentSpec(0, 1.0, 0, 0, true); // 100% dup rate
        var imp = new NetworkImpairmentModel(spec, new Random(1));

        List<SensorNode.RawReport> input = makeReports(50, 0);
        List<SensorNode.RawReport> output = imp.apply(input);

        assertTrue(output.size() > input.size(),
                "100% duplication should produce more reports than input");
        assertEquals(input.size() * 2, output.size(),
                "Each report should be duplicated exactly once");
    }

    @Test
    @DisplayName("Reordering changes sequence order within window")
    void reorderingChangesOrder() {
        var spec = new ScenarioConfig.ImpairmentSpec(0, 0, 0, 500, true); // 500ms reorder window
        var imp = new NetworkImpairmentModel(spec, new Random(42));

        // Generate reports spaced 10ms apart, window = 500ms -> ~50 reports per window
        List<SensorNode.RawReport> input = makeReports(200, 0); // 200 * 10ms = 2000ms
        List<SensorNode.RawReport> output = imp.apply(input);
        output.addAll(imp.flush());

        assertEquals(input.size(), output.size(), "Reordering should not lose or add reports");

        // Check that at least some are out of order
        boolean anyOutOfOrder = false;
        for (int i = 1; i < output.size(); i++) {
            if (output.get(i).sequenceNumber() < output.get(i - 1).sequenceNumber()) {
                anyOutOfOrder = true;
                break;
            }
        }
        assertTrue(anyOutOfOrder, "Reordering should produce at least one out-of-order pair");
    }

    @Test
    @DisplayName("Jitter shifts timestamps")
    void jitterShiftsTimestamps() {
        var spec = new ScenarioConfig.ImpairmentSpec(0, 0, 100, 0, true); // 100ms jitter
        var imp = new NetworkImpairmentModel(spec, new Random(42));

        List<SensorNode.RawReport> input = makeReports(100, 0);
        List<SensorNode.RawReport> output = imp.apply(input);

        boolean anyShifted = false;
        for (int i = 0; i < input.size(); i++) {
            if (input.get(i).timestampMs() != output.get(i).timestampMs()) {
                anyShifted = true;
                break;
            }
        }
        assertTrue(anyShifted, "Jitter should shift at least some timestamps");
    }

    @Test
    @DisplayName("Impairments are reproducible with same seed")
    void impairmentReproducibility() {
        var spec = new ScenarioConfig.ImpairmentSpec(0.1, 0.05, 50, 200, true);
        List<SensorNode.RawReport> input = makeReports(200, 0);

        var imp1 = new NetworkImpairmentModel(spec, new Random(99));
        List<SensorNode.RawReport> out1 = imp1.apply(input);
        out1.addAll(imp1.flush());

        var imp2 = new NetworkImpairmentModel(spec, new Random(99));
        List<SensorNode.RawReport> out2 = imp2.apply(input);
        out2.addAll(imp2.flush());

        assertEquals(out1.size(), out2.size(), "Same seed should produce same count");
        for (int i = 0; i < out1.size(); i++) {
            assertEquals(out1.get(i).sequenceNumber(), out2.get(i).sequenceNumber(),
                    "Same seed should produce same sequence at index " + i);
        }
    }

    // ---- Helpers ----

    /** Generate n synthetic reports spaced 10ms apart starting at offsetMs. */
    private static List<SensorNode.RawReport> makeReports(int n, long offsetMs) {
        List<SensorNode.RawReport> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new SensorNode.RawReport(
                    "S1", i + offsetMs, offsetMs + i * 10L,
                    0, 0, 1000 + i, 0.5, 5.0,
                    30.0, 0.01, false, "T-" + i
            ));
        }
        return list;
    }
}
