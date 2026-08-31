package com.vanguard.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatewayTest {

    // ================================================================
    // PacketValidator
    // ================================================================

    @Nested
    @DisplayName("PacketValidator")
    class ValidatorTests {

        PacketValidator validator = new PacketValidator(30_000);
        long now = 100_000L;

        PacketValidator.DecodedReport valid() {
            return new PacketValidator.DecodedReport(
                    "SENSOR-A", now - 500, 100.0, 200.0, 1500.0, 0.75, 5.0, 42);
        }

        @Test
        @DisplayName("Valid report passes")
        void validPasses() {
            assertTrue(validator.validate(valid(), now).isEmpty());
        }

        @Test
        @DisplayName("Blank sensor_id rejected")
        void blankSensorId() {
            var r = new PacketValidator.DecodedReport("", now, 0, 0, 100, 0.5, 5, 1);
            assertFalse(validator.validate(r, now).isEmpty());
        }

        @Test
        @DisplayName("Null sensor_id rejected")
        void nullSensorId() {
            var r = new PacketValidator.DecodedReport(null, now, 0, 0, 100, 0.5, 5, 1);
            assertFalse(validator.validate(r, now).isEmpty());
        }

        @Test
        @DisplayName("Non-positive timestamp rejected")
        void nonPositiveTimestamp() {
            var r = new PacketValidator.DecodedReport("S1", 0, 0, 0, 100, 0.5, 5, 1);
            assertFalse(validator.validate(r, now).isEmpty());
        }

        @Test
        @DisplayName("Stale report rejected")
        void staleReport() {
            var r = new PacketValidator.DecodedReport("S1", now - 60_000, 0, 0, 100, 0.5, 5, 1);
            List<String> reasons = validator.validate(r, now);
            assertFalse(reasons.isEmpty());
            assertTrue(reasons.getFirst().contains("stale"));
        }

        @Test
        @DisplayName("Future timestamp rejected")
        void futureTimestamp() {
            var r = new PacketValidator.DecodedReport("S1", now + 10_000, 0, 0, 100, 0.5, 5, 1);
            List<String> reasons = validator.validate(r, now);
            assertFalse(reasons.isEmpty());
            assertTrue(reasons.getFirst().contains("future"));
        }

        @Test
        @DisplayName("Negative range rejected")
        void negativeRange() {
            var r = new PacketValidator.DecodedReport("S1", now, 0, 0, -50, 0.5, 5, 1);
            assertFalse(validator.validate(r, now).isEmpty());
        }

        @Test
        @DisplayName("NaN azimuth rejected")
        void nanAzimuth() {
            var r = new PacketValidator.DecodedReport("S1", now, 0, 0, 100, Double.NaN, 5, 1);
            assertFalse(validator.validate(r, now).isEmpty());
        }

        @Test
        @DisplayName("Azimuth beyond 2pi rejected")
        void azimuthOutOfRange() {
            var r = new PacketValidator.DecodedReport("S1", now, 0, 0, 100, 7.0, 5, 1);
            assertFalse(validator.validate(r, now).isEmpty());
        }

        @Test
        @DisplayName("Negative sequence_number rejected")
        void negativeSeq() {
            var r = new PacketValidator.DecodedReport("S1", now, 0, 0, 100, 0.5, 5, -1);
            assertFalse(validator.validate(r, now).isEmpty());
        }

        @Test
        @DisplayName("Disabled staleness check accepts old reports")
        void disabledStaleness() {
            var lenient = new PacketValidator(Long.MAX_VALUE);
            var r = new PacketValidator.DecodedReport("S1", 1, 0, 0, 100, 0.5, 5, 1);
            assertTrue(lenient.validate(r, now).isEmpty());
        }

        @Test
        @DisplayName("Multiple violations reported together")
        void multipleViolations() {
            var r = new PacketValidator.DecodedReport("", -1, 0, 0, -10, Double.NaN, 5, -5);
            List<String> reasons = validator.validate(r, now);
            assertTrue(reasons.size() >= 3, "Should catch multiple issues: " + reasons);
        }
    }

    // ================================================================
    // SequenceTracker
    // ================================================================

    @Nested
    @DisplayName("SequenceTracker")
    class SequenceTests {

        SequenceTracker tracker;

        @BeforeEach
        void init() { tracker = new SequenceTracker(); }

        @Test
        @DisplayName("First report from a sensor is accepted")
        void firstAccepted() {
            assertEquals(SequenceTracker.SequenceVerdict.ACCEPT,
                    tracker.check("S1", 0));
        }

        @Test
        @DisplayName("Sequential reports accepted")
        void sequential() {
            tracker.check("S1", 0);
            assertEquals(SequenceTracker.SequenceVerdict.ACCEPT, tracker.check("S1", 1));
            assertEquals(SequenceTracker.SequenceVerdict.ACCEPT, tracker.check("S1", 2));
        }

        @Test
        @DisplayName("Duplicate sequence rejected")
        void duplicate() {
            tracker.check("S1", 5);
            assertEquals(SequenceTracker.SequenceVerdict.DUPLICATE, tracker.check("S1", 5));
            assertEquals(1, tracker.getDuplicateCount("S1"));
        }

        @Test
        @DisplayName("Old sequence rejected as duplicate")
        void oldSequence() {
            tracker.check("S1", 10);
            assertEquals(SequenceTracker.SequenceVerdict.DUPLICATE, tracker.check("S1", 3));
        }

        @Test
        @DisplayName("Gap detected but report accepted")
        void gapDetected() {
            tracker.check("S1", 0);
            assertEquals(SequenceTracker.SequenceVerdict.GAP_THEN_ACCEPT,
                    tracker.check("S1", 5));
            assertEquals(1, tracker.getGapCount("S1"));
        }

        @Test
        @DisplayName("Different sensors tracked independently")
        void independentSensors() {
            tracker.check("S1", 10);
            tracker.check("S2", 10);
            assertEquals(SequenceTracker.SequenceVerdict.ACCEPT, tracker.check("S1", 11));
            assertEquals(SequenceTracker.SequenceVerdict.DUPLICATE, tracker.check("S2", 5));
        }
    }

    // ================================================================
    // InMemoryReportSink (backpressure)
    // ================================================================

    @Nested
    @DisplayName("InMemoryReportSink")
    class SinkTests {

        @Test
        @DisplayName("Offers succeed up to capacity")
        void offersUpToCapacity() {
            InMemoryReportSink sink = new InMemoryReportSink(3);
            var r = new PacketValidator.DecodedReport("S1", 1000, 0, 0, 100, 0.5, 5, 0);
            assertTrue(sink.offer(r));
            assertTrue(sink.offer(r));
            assertTrue(sink.offer(r));
            assertEquals(3, sink.size());
        }

        @Test
        @DisplayName("Offer returns false when full (backpressure)")
        void backpressure() {
            InMemoryReportSink sink = new InMemoryReportSink(2);
            var r = new PacketValidator.DecodedReport("S1", 1000, 0, 0, 100, 0.5, 5, 0);
            sink.offer(r);
            sink.offer(r);
            assertFalse(sink.offer(r), "Should reject when full");
        }

        @Test
        @DisplayName("Drain empties the queue")
        void drain() {
            InMemoryReportSink sink = new InMemoryReportSink(10);
            var r = new PacketValidator.DecodedReport("S1", 1000, 0, 0, 100, 0.5, 5, 0);
            sink.offer(r);
            sink.offer(r);
            var drained = sink.drain();
            assertEquals(2, drained.size());
            assertEquals(0, sink.size());
        }
    }

    // ================================================================
    // GatewayMetrics
    // ================================================================

    @Nested
    @DisplayName("GatewayMetrics")
    class MetricsTests {

        @Test
        @DisplayName("Counters increment correctly")
        void counters() {
            GatewayMetrics m = new GatewayMetrics();
            m.recordReceived(100);
            m.recordReceived(200);
            m.recordAccepted();
            m.recordMalformed();
            m.recordDuplicate();
            m.recordDropped();
            assertEquals(2, m.getPacketsReceived());
            assertEquals(300, m.getBytesReceived());
            assertEquals(1, m.getPacketsAccepted());
            assertEquals(1, m.getPacketsMalformed());
            assertEquals(1, m.getPacketsDuplicate());
            assertEquals(1, m.getPacketsDropped());
        }
    }
}
