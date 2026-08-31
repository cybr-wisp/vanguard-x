package com.vanguard.spatial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SpatialTest {

    // ================================================================
    // Day 11: RestrictedZone + GeofenceEngine
    // ================================================================

    @Nested
    @DisplayName("RestrictedZone")
    class ZoneTests {

        // Zone: rectangle from (100,100) to (200,200)
        // Warning buffer: 50m, Advisory buffer: 100m
        RestrictedZone zone = RestrictedZone.rectangle(
                "ZONE-1", 100, 100, 200, 200, 50, 100);

        @Test
        @DisplayName("Point inside polygon -> BREACH")
        void insideIsBreach() {
            assertEquals(ZoneClassification.BREACH, zone.classify(150, 150));
        }

        @Test
        @DisplayName("Point on boundary -> BREACH")
        void boundaryIsBreach() {
            assertEquals(ZoneClassification.BREACH, zone.classify(100, 150));
        }

        @Test
        @DisplayName("Point in warning buffer -> WARNING")
        void warningBuffer() {
            // 30m outside the polygon (within 50m warning buffer)
            assertEquals(ZoneClassification.WARNING, zone.classify(70, 150));
        }

        @Test
        @DisplayName("Point in advisory buffer -> ADVISORY")
        void advisoryBuffer() {
            // 70m outside (outside 50m warning, inside 100m advisory)
            assertEquals(ZoneClassification.ADVISORY, zone.classify(30, 150));
        }

        @Test
        @DisplayName("Point far outside -> CLEAR")
        void farOutsideIsClear() {
            assertEquals(ZoneClassification.CLEAR, zone.classify(-500, -500));
        }

        @Test
        @DisplayName("Signed distance negative inside, positive outside")
        void signedDistance() {
            double inside  = zone.signedDistance(150, 150);
            double outside = zone.signedDistance(0, 0);
            assertTrue(inside <= 0, "Inside should be non-positive: " + inside);
            assertTrue(outside > 0, "Outside should be positive: " + outside);
        }
    }

    @Nested
    @DisplayName("GeofenceEngine")
    class EngineTests {

        @Test
        @DisplayName("Multiple zones evaluated independently")
        void multipleZones() {
            GeofenceEngine engine = new GeofenceEngine();
            engine.addZone(RestrictedZone.rectangle("Z1", 0, 0, 100, 100, 20, 50));
            engine.addZone(RestrictedZone.rectangle("Z2", 500, 500, 600, 600, 20, 50));

            var result = engine.classify(50, 50);
            assertEquals(ZoneClassification.BREACH, result.get("Z1"));
            assertEquals(ZoneClassification.CLEAR, result.get("Z2"));
        }

        @Test
        @DisplayName("Worst classification picks highest severity")
        void worstClassification() {
            GeofenceEngine engine = new GeofenceEngine();
            engine.addZone(RestrictedZone.rectangle("Z1", 0, 0, 100, 100, 20, 50));
            engine.addZone(RestrictedZone.rectangle("Z2", 80, 80, 200, 200, 20, 50));

            // Point at (90,90) is inside both zones -> BREACH
            assertEquals(ZoneClassification.BREACH, engine.worstClassification(90, 90));
        }
    }

    // ================================================================
    // Day 12: AlertStateMachine
    // ================================================================

    @Nested
    @DisplayName("AlertStateMachine")
    class AlertTests {

        AlertStateMachine sm = new AlertStateMachine();

        @Test
        @DisplayName("First BREACH emits ZONE_ENTRY")
        void firstBreachEmitsEntry() {
            var event = sm.update("T1", "Z1", ZoneClassification.BREACH, 1000, 50, 50);
            assertTrue(event.isPresent());
            assertEquals(TrackEvent.EventType.ZONE_ENTRY, event.get().type());
        }

        @Test
        @DisplayName("Repeated BREACH emits nothing (dedup)")
        void repeatedBreachNoEvent() {
            sm.update("T1", "Z1", ZoneClassification.BREACH, 1000, 50, 50);
            var second = sm.update("T1", "Z1", ZoneClassification.BREACH, 2000, 51, 51);
            assertTrue(second.isEmpty(), "Duplicate BREACH should not emit");
        }

        @Test
        @DisplayName("1000 repeated BREACHes emit exactly 1 event")
        void thousandBreachesOneEvent() {
            int eventCount = 0;
            for (int i = 0; i < 1000; i++) {
                var e = sm.update("T1", "Z1", ZoneClassification.BREACH, i * 100, 50, 50);
                if (e.isPresent()) eventCount++;
            }
            assertEquals(1, eventCount, "Only the first BREACH should emit");
        }

        @Test
        @DisplayName("BREACH -> CLEAR emits ZONE_EXIT")
        void breachToClearEmitsExit() {
            sm.update("T1", "Z1", ZoneClassification.BREACH, 1000, 50, 50);
            var exit = sm.update("T1", "Z1", ZoneClassification.CLEAR, 2000, 300, 300);
            assertTrue(exit.isPresent());
            assertEquals(TrackEvent.EventType.ZONE_EXIT, exit.get().type());
            assertEquals(ZoneClassification.BREACH, exit.get().previousState());
            assertEquals(ZoneClassification.CLEAR, exit.get().newState());
        }

        @Test
        @DisplayName("CLEAR -> ADVISORY emits ZONE_APPROACH")
        void clearToAdvisoryEmitsApproach() {
            var event = sm.update("T1", "Z1", ZoneClassification.ADVISORY, 1000, 50, 50);
            assertTrue(event.isPresent());
            assertEquals(TrackEvent.EventType.ZONE_APPROACH, event.get().type());
        }

        @Test
        @DisplayName("Full crossing: CLEAR -> ADVISORY -> WARNING -> BREACH -> EXIT")
        void fullCrossingSequence() {
            var e1 = sm.update("T1", "Z1", ZoneClassification.ADVISORY, 1000, 80, 150);
            var e2 = sm.update("T1", "Z1", ZoneClassification.WARNING, 2000, 90, 150);
            var e3 = sm.update("T1", "Z1", ZoneClassification.BREACH, 3000, 110, 150);
            var e4 = sm.update("T1", "Z1", ZoneClassification.WARNING, 4000, 90, 150);

            assertEquals(TrackEvent.EventType.ZONE_APPROACH, e1.get().type());
            assertTrue(e2.isEmpty() || e2.get().type() != TrackEvent.EventType.ZONE_ENTRY,
                    "WARNING is not a ZONE_ENTRY");
            assertEquals(TrackEvent.EventType.ZONE_ENTRY, e3.get().type());
            assertEquals(TrackEvent.EventType.ZONE_EXIT, e4.get().type());
        }

        @Test
        @DisplayName("Different tracks are independent")
        void independentTracks() {
            sm.update("T1", "Z1", ZoneClassification.BREACH, 1000, 50, 50);
            var t2Event = sm.update("T2", "Z1", ZoneClassification.BREACH, 1000, 60, 60);
            assertTrue(t2Event.isPresent(), "T2 should emit its own ZONE_ENTRY");
        }

        @Test
        @DisplayName("Different zones are independent")
        void independentZones() {
            sm.update("T1", "Z1", ZoneClassification.BREACH, 1000, 50, 50);
            var z2Event = sm.update("T1", "Z2", ZoneClassification.BREACH, 1000, 550, 550);
            assertTrue(z2Event.isPresent(), "Z2 should emit its own ZONE_ENTRY");
        }

        @Test
        @DisplayName("removeTrack cleans up state")
        void removeTrack() {
            sm.update("T1", "Z1", ZoneClassification.BREACH, 1000, 50, 50);
            sm.removeTrack("T1");
            assertEquals(ZoneClassification.CLEAR, sm.getCurrentState("T1", "Z1"));
        }
    }

    // ================================================================
    // Day 12: TrackEventPublisher
    // ================================================================

    @Nested
    @DisplayName("TrackEventPublisher")
    class PublisherTests {

        @Test
        @DisplayName("Zone crossing scenario emits correct event sequence")
        void zoneCrossingScenario() {
            GeofenceEngine engine = new GeofenceEngine();
            engine.addZone(RestrictedZone.rectangle("ALPHA", 100, 100, 200, 200, 50, 100));
            TrackEventPublisher pub = new TrackEventPublisher(engine);

            List<TrackEvent> collected = new java.util.ArrayList<>();
            pub.addListener(collected::add);

            // Track approaches from far away
            pub.evaluateTrack("T1", -100, 150, 0);     // CLEAR
            pub.evaluateTrack("T1", 10, 150, 1000);     // ADVISORY
            pub.evaluateTrack("T1", 60, 150, 2000);     // WARNING
            pub.evaluateTrack("T1", 110, 150, 3000);    // BREACH
            pub.evaluateTrack("T1", 150, 150, 4000);    // still BREACH (no event)
            pub.evaluateTrack("T1", 150, 150, 5000);    // still BREACH (no event)
            pub.evaluateTrack("T1", 210, 150, 6000);    // exits to WARNING
            pub.evaluateTrack("T1", 350, 150, 7000);    // CLEAR

            // Should have: APPROACH, ENTRY, EXIT
            assertEquals(3, collected.size(),
                    "Should emit exactly 3 events: " + collected);
            assertEquals(TrackEvent.EventType.ZONE_APPROACH, collected.get(0).type());
            assertEquals(TrackEvent.EventType.ZONE_ENTRY, collected.get(1).type());
            assertEquals(TrackEvent.EventType.ZONE_EXIT, collected.get(2).type());
        }
    }
}
