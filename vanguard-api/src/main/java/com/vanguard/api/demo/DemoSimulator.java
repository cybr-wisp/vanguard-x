package com.vanguard.api.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanguard.api.repository.TrackRepository;
import com.vanguard.api.websocket.WebSocketConfig.EventStreamHandler;
import com.vanguard.api.websocket.WebSocketConfig.HealthStreamHandler;
import com.vanguard.api.websocket.WebSocketConfig.TrackStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Self-contained demo that generates realistic fused track data and zone
 * events without requiring Kafka or the full pipeline. Activated with:
 *
 *   vanguard.demo.enabled=true
 *
 * Simulates 5 targets with turning trajectories, multi-sensor fusion with
 * Kalman-like gain, track lifecycle (TENTATIVE -> CONFIRMED -> COASTING),
 * and geofence zone transitions. Publishes at ~15 Hz through the same
 * WebSocket handlers the real pipeline uses.
 */
@Component
@ConditionalOnProperty(name = "vanguard.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoSimulator {

    private static final Logger log = LoggerFactory.getLogger(DemoSimulator.class);

    private final TrackStreamHandler trackHandler;
    private final EventStreamHandler eventHandler;
    private final HealthStreamHandler healthHandler;
    private final TrackRepository trackRepo;
    private final ObjectMapper mapper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    // --- Orbital scenario targets ---
    // Each target orbits a center point. Some orbits cross the restricted zone.
    //          id,    centerLng,  centerLat,  radiusLng, radiusLat, angularRate, phaseOffset
    private static final double[][] ORBITS = {
            { 1001, -117.20, 34.76,  0.14, 0.06,  0.018,  0.0   },  // wide orbit, crosses zone
            { 1002, -117.10, 34.74,  0.08, 0.05, -0.022,  1.2   },  // tighter, crosses zone
            { 1003, -117.25, 34.72,  0.12, 0.07,  0.015,  2.5   },  // wide ellipse
            { 1004, -117.15, 34.78,  0.10, 0.04, -0.020,  3.8   },  // near zone edge
            { 1005, -117.08, 34.74,  0.06, 0.06,  0.025,  5.0   },  // tight circle near zone
    };

    // Zone R-21
    private static final double ZONE_LNG = -117.05, ZONE_LAT = 34.755;
    private static final double ZONE_RX = 0.06, ZONE_RY = 0.04;

    // Per-target mutable state
    private double[][] trkState; // [i] = { estLng, estLat, estVlng, estVlat, unc, hits }
    private String[] trkLifecycle;
    private boolean[] inZone;
    private double[] prevTgtLng, prevTgtLat;
    private int tick;
    private long startMs;

    public DemoSimulator(TrackStreamHandler trackHandler,
                          EventStreamHandler eventHandler,
                          HealthStreamHandler healthHandler,
                          TrackRepository trackRepo,
                          ObjectMapper mapper) {
        this.trackHandler = trackHandler;
        this.eventHandler = eventHandler;
        this.healthHandler = healthHandler;
        this.trackRepo = trackRepo;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running.set(true);
        startMs = System.currentTimeMillis();
        tick = 0;

        // Init target state
        trkState = new double[ORBITS.length][6];
        trkLifecycle = new String[ORBITS.length];
        inZone = new boolean[ORBITS.length];
        prevTgtLng = new double[ORBITS.length];
        prevTgtLat = new double[ORBITS.length];
        for (int i = 0; i < ORBITS.length; i++) {
            trkLifecycle[i] = "NONE";
            inZone[i] = false;
            prevTgtLng[i] = ORBITS[i][1] + ORBITS[i][3] * Math.cos(ORBITS[i][6]);
            prevTgtLat[i] = ORBITS[i][2] + ORBITS[i][4] * Math.sin(ORBITS[i][6]);
        }

        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("demo-sim-", 0).factory());
        executor.scheduleAtFixedRate(this::step, 500, 70, TimeUnit.MILLISECONDS);

        log.info("Demo simulator started (5 targets, ~15 Hz)");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) executor.shutdownNow();
        log.info("Demo simulator stopped at tick {}", tick);
    }

    private void step() {
        if (!running.get()) return;
        tick++;
        long nowMs = System.currentTimeMillis();
        Random rng = ThreadLocalRandom.current();

        for (int i = 0; i < ORBITS.length; i++) {
            double[] orb = ORBITS[i];
            String trackId = "T-" + (int) orb[0];

            // Compute ground truth position on the orbital ellipse
            double angle = orb[5] * tick + orb[6]; // angularRate * tick + phase
            double trueLng = orb[1] + orb[3] * Math.cos(angle);
            double trueLat = orb[2] + orb[4] * Math.sin(angle);

            // Ground truth velocity (derivative of position)
            double trueVlng = trueLng - prevTgtLng[i];
            double trueVlat = trueLat - prevTgtLat[i];
            prevTgtLng[i] = trueLng;
            prevTgtLat[i] = trueLat;

            // Simulate 3 sensor observations with noise
            double obsLng = 0, obsLat = 0;
            for (int s = 0; s < 3; s++) {
                obsLng += trueLng + rng.nextGaussian() * 0.002;
                obsLat += trueLat + rng.nextGaussian() * 0.0015;
            }
            obsLng /= 3;
            obsLat /= 3;

            double[] trk = trkState[i];
            if ("NONE".equals(trkLifecycle[i])) {
                trk[0] = obsLng; trk[1] = obsLat;
                trk[2] = 0; trk[3] = 0;
                trk[4] = 40; trk[5] = 1;
                trkLifecycle[i] = "TENTATIVE";
            } else {
                double k = Math.min(0.4, 1.0 / (1 + trk[4] * 0.01));
                double predLng = trk[0] + trk[2];
                double predLat = trk[1] + trk[3];
                trk[0] = predLng + k * (obsLng - predLng);
                trk[1] = predLat + k * (obsLat - predLat);
                trk[2] = 0.85 * trk[2] + 0.15 * (trk[0] - predLng);
                trk[3] = 0.85 * trk[3] + 0.15 * (trk[1] - predLat);
                trk[4] = Math.max(8, trk[4] * (1 - k * 0.5));
                trk[5]++;

                if (trk[5] >= 3 && "TENTATIVE".equals(trkLifecycle[i])) {
                    trkLifecycle[i] = "CONFIRMED";
                }
                if ("COASTING".equals(trkLifecycle[i])) {
                    trkLifecycle[i] = "CONFIRMED";
                }
            }

            // Publish fused track
            try {
                double hdg = Math.atan2(trueVlng, trueVlat) * 57.3;
                double spd = Math.sqrt(trueVlng * trueVlng + trueVlat * trueVlat) * 111000;

                Map<String, Object> track = new LinkedHashMap<>();
                track.put("trackId", trackId);
                track.put("px", trk[0]);
                track.put("py", trk[1]);
                track.put("vx", trueVlng * 111000);
                track.put("vy", trueVlat * 111000);
                track.put("state", trkLifecycle[i]);
                track.put("uncertainty", trk[4]);
                track.put("lastUpdateMs", nowMs);
                track.put("contributingSensors", List.of("SSA-01", "SSB-02", "SSC-03"));
                track.put("ellipseMajor", trk[4] * 3);
                track.put("ellipseMinor", trk[4] * 2);
                track.put("ellipseAngle", hdg);

                String json = mapper.writeValueAsString(track);
                trackHandler.broadcast(json);

                try {
                    trackRepo.updateTrack(trackId, trk[0], trk[1],
                            trueVlng * 111000, trueVlat * 111000,
                            trkLifecycle[i], trk[4], nowMs);
                } catch (Exception ignored) {}
            } catch (Exception e) {
                log.warn("Failed to publish track: {}", e.getMessage());
            }

            // Zone check
            if (!"NONE".equals(trkLifecycle[i])) {
                double dx = (trk[0] - ZONE_LNG) / ZONE_RX;
                double dy = (trk[1] - ZONE_LAT) / ZONE_RY;
                boolean inside = dx * dx + dy * dy <= 1;

                if (inside && !inZone[i]) {
                    publishEvent(trackId, "ZONE_ENTRY", "R-21", trk[0], trk[1], nowMs);
                } else if (!inside && inZone[i]) {
                    publishEvent(trackId, "ZONE_EXIT", "R-21", trk[0], trk[1], nowMs);
                }
                inZone[i] = inside;
            }
        }

        // Broadcast health metrics every ~1.5s
        if (tick % 20 == 0) {
            broadcastHealth(nowMs);
        }
    }

    private void publishEvent(String trackId, String type, String zoneId,
                               double px, double py, long nowMs) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", UUID.randomUUID().toString().substring(0, 8));
            event.put("trackId", trackId);
            event.put("zoneId", zoneId);
            event.put("type", type);
            event.put("timestampMs", nowMs);
            event.put("previousState", "");
            event.put("newState", type);
            event.put("px", px);
            event.put("py", py);

            eventHandler.broadcast(mapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to publish event: {}", e.getMessage());
        }
    }

    private void broadcastHealth(long nowMs) {
        try {
            int active = 0, confirmed = 0, coasting = 0;
            for (String s : trkLifecycle) {
                if (!"NONE".equals(s) && !"DROPPED".equals(s)) {
                    active++;
                    if ("CONFIRMED".equals(s)) confirmed++;
                    else if ("COASTING".equals(s)) coasting++;
                }
            }

            Map<String, Object> health = new LinkedHashMap<>();
            health.put("throughputReportsPerSec", 15 * ORBITS.length);
            health.put("p50LatencyMs", 5.5);
            health.put("p95LatencyMs", 12.0);
            health.put("p99LatencyMs", 22.0);
            health.put("activeTracks", active);
            health.put("confirmedTracks", confirmed);
            health.put("coastingTracks", coasting);
            health.put("queueDepth", 0);
            health.put("kafkaLag", 0);
            health.put("packetsDropped", 0);
            health.put("uptimeMs", nowMs - startMs);

            healthHandler.broadcast(mapper.writeValueAsString(health));
        } catch (Exception e) {
            log.warn("Failed to broadcast health: {}", e.getMessage());
        }
    }
}
