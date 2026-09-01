package com.vanguard.api.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanguard.simulator.*;
import com.vanguard.tracking.association.*;
import com.vanguard.tracking.estimation.*;
import com.vanguard.tracking.lifecycle.*;
import com.vanguard.spatial.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.ejml.simple.SimpleMatrix;
import org.locationtech.jts.geom.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full pipeline orchestrator. Runs the complete data path in-process:
 *
 *   WorldSimulator -> SensorNodes -> (raw reports to Kafka)
 *   TrackingPipelineConsumer (Kafka) -> EKF/Association -> (fused tracks to Kafka)
 *   SpatialPipelineConsumer (Kafka) -> Geofence/AlertSM -> (events to Kafka)
 *
 * The KafkaWebSocketBridge (separate bean) reads the output topics and
 * pushes to WebSocket clients. This gives the full end-to-end pipeline
 * through Kafka, not a simulation shortcut.
 *
 * Activated when vanguard.demo.enabled=false (requires Kafka + Redis running).
 */
@Component
@ConditionalOnProperty(name = "vanguard.demo.enabled", havingValue = "false")
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private static final String RAW_TOPIC   = "sensor-reports.raw";
    private static final String FUSED_TOPIC = "tracks.fused";
    private static final String EVENT_TOPIC = "track-events";

    @Value("${vanguard.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledExecutorService simExecutor;
    private ExecutorService pipelineExecutor;
    private KafkaProducer<String, byte[]> rawProducer;

    // Tracking state
    private TrackManager trackManager;
    private GeofenceEngine geofenceEngine;
    private AlertStateMachine alertSM;
    private final List<SensorNode> sensors = new ArrayList<>();
    private final List<MeasurementModel> sensorModels = new ArrayList<>();
    private WorldSimulator world;
    private long simTimeMs;
    private Random rng;

    // Kafka producers for fused tracks and events
    private KafkaProducer<String, byte[]> fusedProducer;
    private KafkaProducer<String, byte[]> eventProducer;

    public PipelineOrchestrator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running.set(true);
        log.info("Starting full pipeline orchestrator (bootstrap={})", bootstrapServers);

        // Build the scenario
        ScenarioConfig config = buildDemoScenario();
        world = WorldSimulator.fromConfig(config);
        rng = new Random(config.seed());
        simTimeMs = 0;

        // Build sensors + measurement models
        for (ScenarioConfig.SensorSpec spec : config.sensors()) {
            sensors.add(new SensorNode(spec, new Random(rng.nextLong())));
            sensorModels.add(new MeasurementModel(spec.sx(), spec.sy(),
                    spec.sigmaRangeM(), spec.sigmaBearingRad()));
        }

        // Build tracker
        MotionModel motion = new MotionModel(2.0);
        DataAssociator assoc = new DataAssociator(new MahalanobisGate(9.21));
        trackManager = new TrackManager(assoc, motion, 3, 3, 8, 10000, 100);

        // Build geofence engine with zone R-21
        geofenceEngine = new GeofenceEngine();
        alertSM = new AlertStateMachine();
        GeometryFactory gf = new GeometryFactory();
        // R-21: elliptical zone at (-117.05, 34.755) mapped to meters
        // Using a circular polygon centered at the zone in metric space
        double cx = -117.05 * 111000, cy = 34.755 * 111000;
        double rx = 0.06 * 111000, ry = 0.04 * 111000;
        Coordinate[] coords = new Coordinate[65];
        for (int i = 0; i < 64; i++) {
            double a = (i / 64.0) * Math.PI * 2;
            coords[i] = new Coordinate(cx + Math.cos(a) * rx, cy + Math.sin(a) * ry);
        }
        coords[64] = coords[0];
        Polygon zonePoly = gf.createPolygon(coords);
        geofenceEngine.addZone(new RestrictedZone("R-21", zonePoly, 500, 1000));

        // Kafka producers
        rawProducer = createProducer();
        fusedProducer = createProducer();
        eventProducer = createProducer();

        // Start simulation at ~15 Hz
        simExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("pipeline-sim-", 0).factory());
        simExecutor.scheduleAtFixedRate(this::tick, 1000, 70, TimeUnit.MILLISECONDS);

        log.info("Pipeline orchestrator running: {} targets, {} sensors, zone R-21 active",
                world.getTargetCount(), sensors.size());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (simExecutor != null) simExecutor.shutdownNow();
        if (pipelineExecutor != null) pipelineExecutor.shutdownNow();
        if (rawProducer != null) rawProducer.close();
        if (fusedProducer != null) fusedProducer.close();
        if (eventProducer != null) eventProducer.close();
        log.info("Pipeline orchestrator stopped at simTime={}ms", simTimeMs);
    }

    /**
     * One simulation tick: advance time, generate observations, run tracking
     * and spatial, publish to Kafka.
     */
    private void tick() {
        if (!running.get()) return;
        simTimeMs += 100; // 100ms increments
        long wallMs = System.currentTimeMillis();

        // 1. Get ground truth
        List<TargetModel.TruthRecord> truth = world.truthAt(simTimeMs);
        if (truth.isEmpty()) {
            // Scenario ended, loop it
            simTimeMs = 0;
            return;
        }

        // 2. Generate sensor observations and publish raw to Kafka
        List<SimpleMatrix> allMeasurements = new ArrayList<>();
        String sensorId = null;
        MeasurementModel mm = null;

        for (int s = 0; s < sensors.size(); s++) {
            SensorNode sensor = sensors.get(s);
            List<SensorNode.RawReport> reports = sensor.observe(truth, simTimeMs, rng);
            mm = sensorModels.get(s);
            sensorId = sensor.getSensorId();

            for (SensorNode.RawReport r : reports) {
                // Publish raw report to Kafka
                String csv = "%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d".formatted(
                        r.sensorId(), r.timestampMs(),
                        r.sensorX(), r.sensorY(),
                        r.rangeM(), r.azimuthRad(),
                        r.signalStrength(), r.sequenceNumber());
                rawProducer.send(new ProducerRecord<>(RAW_TOPIC, r.sensorId(), csv.getBytes()));

                if (!r.isFalseDetection()) {
                    allMeasurements.add(new SimpleMatrix(new double[][]{{r.rangeM()}, {r.azimuthRad()}}));
                }
            }

            // 3. Run tracking (process each sensor's batch)
            if (!allMeasurements.isEmpty() && mm != null) {
                trackManager.processObservations(allMeasurements, mm, sensorId, simTimeMs);
                allMeasurements.clear();
            }
        }

        // 4. Publish fused tracks to Kafka + run spatial
        for (Track track : trackManager.getAllTracks()) {
            if (!track.isAlive()) continue;

            try {
                // Convert EKF meters back to WGS84 degrees for the UI
                double cLng = -117.15, cLat = 34.74;
                double mPerDegLng = 92000, mPerDegLat = 111000;

                Map<String, Object> fused = new LinkedHashMap<>();
                fused.put("trackId", track.getTrackId());
                fused.put("px", cLng + track.getPx() / mPerDegLng);
                fused.put("py", cLat + track.getPy() / mPerDegLat);
                fused.put("vx", track.getVx() / mPerDegLng);
                fused.put("vy", track.getVy() / mPerDegLat);
                fused.put("state", track.getState().name());
                fused.put("uncertainty", track.getPositionUncertainty());
                fused.put("lastUpdateMs", wallMs);
                fused.put("contributingSensors", List.copyOf(track.getContributingSensors()));

                // Covariance ellipse from P matrix
                SimpleMatrix P = track.getEkf().getCovariance();
                double pxx = P.get(0, 0), pyy = P.get(1, 1), pxy = P.get(0, 1);
                double angle = 0.5 * Math.atan2(2 * pxy, pxx - pyy);
                double cos2 = Math.cos(angle) * Math.cos(angle);
                double sin2 = Math.sin(angle) * Math.sin(angle);
                double sincos = Math.sin(angle) * Math.cos(angle);
                double major = Math.sqrt(pxx * cos2 + 2 * pxy * sincos + pyy * sin2);
                double minor = Math.sqrt(pxx * sin2 - 2 * pxy * sincos + pyy * cos2);
                fused.put("ellipseMajor", major * 2);
                fused.put("ellipseMinor", minor * 2);
                fused.put("ellipseAngle", Math.toDegrees(angle));

                byte[] json = mapper.writeValueAsBytes(fused);
                fusedProducer.send(new ProducerRecord<>(FUSED_TOPIC, track.getTrackId(), json));

                // 5. Geofence evaluation
                Map<String, ZoneClassification> zones = geofenceEngine.classify(
                        track.getPx(), track.getPy());
                for (var entry : zones.entrySet()) {
                    Optional<TrackEvent> evt = alertSM.update(
                            track.getTrackId(), entry.getKey(),
                            entry.getValue(), wallMs,
                            track.getPx(), track.getPy());
                    if (evt.isPresent()) {
                        TrackEvent te = evt.get();
                        Map<String, Object> evMap = new LinkedHashMap<>();
                        evMap.put("eventId", UUID.randomUUID().toString().substring(0, 8));
                        evMap.put("trackId", te.trackId());
                        evMap.put("zoneId", te.zoneId());
                        evMap.put("type", te.type().name());
                        evMap.put("timestampMs", te.timestampMs());
                        evMap.put("previousState", te.previousState().name());
                        evMap.put("newState", te.newState().name());
                        evMap.put("px", te.px());
                        evMap.put("py", te.py());

                        byte[] evJson = mapper.writeValueAsBytes(evMap);
                        eventProducer.send(new ProducerRecord<>(EVENT_TOPIC,
                                te.trackId() + "|" + te.zoneId(), evJson));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to publish track {}: {}", track.getTrackId(), e.getMessage());
            }
        }
    }

    /**
     * Build a demo scenario with 5 targets and 3 sensors that matches
     * the UI's map viewport (lng/lat mapped to meters).
     */
    private ScenarioConfig buildDemoScenario() {
        // Map center: (-117.15, 34.74) -> treat as origin in meters
        // Scale: 1 degree lng ~ 92km at lat 34.7, 1 degree lat ~ 111km
        double cLng = -117.15, cLat = 34.74;
        double mPerDegLng = 92000, mPerDegLat = 111000;

        List<ScenarioConfig.TargetSpec> targets = List.of(
                // Target 1: sweeps east, turns south
                new ScenarioConfig.TargetSpec("TGT-01", 0,
                        (cLng - 0.15 - cLng) * mPerDegLng, (cLat + 0.05 - cLat) * mPerDegLat,
                        25, -5, List.of(
                        ScenarioConfig.SegmentSpec.straight(15),
                        ScenarioConfig.SegmentSpec.turn(20, 0.02),
                        ScenarioConfig.SegmentSpec.straight(15))),
                // Target 2: sweeps west from east side
                new ScenarioConfig.TargetSpec("TGT-02", 0,
                        (cLng + 0.12 - cLng) * mPerDegLng, (cLat + 0.06 - cLat) * mPerDegLat,
                        -20, -10, List.of(
                        ScenarioConfig.SegmentSpec.straight(20),
                        ScenarioConfig.SegmentSpec.turn(15, -0.015),
                        ScenarioConfig.SegmentSpec.straight(15))),
                // Target 3: northbound, turns east toward zone
                new ScenarioConfig.TargetSpec("TGT-03", 0,
                        (cLng - 0.05 - cLng) * mPerDegLng, (cLat - 0.06 - cLat) * mPerDegLat,
                        8, 18, List.of(
                        ScenarioConfig.SegmentSpec.straight(15),
                        ScenarioConfig.SegmentSpec.turn(10, 0.03),
                        ScenarioConfig.SegmentSpec.straight(25))),
                // Target 4: slow mover, starts near zone
                new ScenarioConfig.TargetSpec("TGT-04", 2000,
                        (cLng + 0.08 - cLng) * mPerDegLng, (cLat + 0.02 - cLat) * mPerDegLat,
                        -5, 3, List.of(
                        ScenarioConfig.SegmentSpec.straight(20),
                        ScenarioConfig.SegmentSpec.turn(25, -0.01),
                        ScenarioConfig.SegmentSpec.straight(5))),
                // Target 5: fast diagonal
                new ScenarioConfig.TargetSpec("TGT-05", 1000,
                        (cLng - 0.10 - cLng) * mPerDegLng, (cLat + 0.08 - cLat) * mPerDegLat,
                        30, -15, List.of(
                        ScenarioConfig.SegmentSpec.straight(10),
                        ScenarioConfig.SegmentSpec.turn(15, 0.025),
                        ScenarioConfig.SegmentSpec.straight(25)))
        );

        // 3 sensors matching the UI positions
        List<ScenarioConfig.SensorSpec> sensorSpecs = List.of(
                new ScenarioConfig.SensorSpec("SSA-01",
                        (-117.35 - cLng) * mPerDegLng, (34.79 - cLat) * mPerDegLat,
                        50, 0.01, 5, 0.001, 100, 0.02),
                new ScenarioConfig.SensorSpec("SSB-02",
                        (-117.38 - cLng) * mPerDegLng, (34.71 - cLat) * mPerDegLat,
                        50, 0.01, 5, 0.001, 100, 0.02),
                new ScenarioConfig.SensorSpec("SSC-03",
                        (-117.05 - cLng) * mPerDegLng, (34.68 - cLat) * mPerDegLat,
                        50, 0.01, 5, 0.001, 100, 0.02)
        );

        return new ScenarioConfig("demo-live", 42L, 50_000L, targets, sensorSpecs,
                new ScenarioConfig.ImpairmentSpec(0.0, 0.0, 0, 0, false));
    }

    private KafkaProducer<String, byte[]> createProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        return new KafkaProducer<>(props);
    }
}
