package com.vanguard.api.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vanguard.gateway.GatewayMetrics;
import com.vanguard.gateway.NettyUdpServer;
import com.vanguard.gateway.PacketValidator;
import com.vanguard.gateway.SequenceTracker;
import com.vanguard.gateway.kafka.KafkaRawReportProducer;
import com.vanguard.protocol.SensorReportProto;
import com.vanguard.simulator.*;
import com.vanguard.spatial.*;
import com.vanguard.spatial.kafka.SpatialPipelineConsumer;
import com.vanguard.tracking.association.DataAssociator;
import com.vanguard.tracking.association.MahalanobisGate;
import com.vanguard.tracking.estimation.MeasurementModel;
import com.vanguard.tracking.estimation.MotionModel;
import com.vanguard.tracking.lifecycle.Track;
import com.vanguard.tracking.lifecycle.TrackManager;
import com.vanguard.tracking.pipeline.TrackingPipelineConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.ejml.simple.SimpleMatrix;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full Vanguard runtime.
 *
 * Actual data path:
 *
 * WorldSimulator
 *   -> SensorNode
 *   -> Protobuf / UDP
 *   -> NettyUdpServer
 *   -> KafkaRawReportProducer
 *   -> sensor-reports.raw
 *   -> TrackingPipelineConsumer
 *   -> EKF / association / lifecycle
 *   -> tracks.fused
 *   -> SpatialPipelineConsumer
 *   -> GeofenceEngine / AlertStateMachine
 *   -> track-events
 *   -> KafkaWebSocketBridge
 *   -> WebSocket clients
 *
 * Activated only when vanguard.demo.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "vanguard.demo.enabled", havingValue = "false")
public class PipelineOrchestrator {

    private static final Logger log =
            LoggerFactory.getLogger(PipelineOrchestrator.class);

    private static final double CENTER_LNG = -117.15;
    private static final double CENTER_LAT = 34.74;
    private static final double METERS_PER_DEG_LNG = 92_000.0;
    private static final double METERS_PER_DEG_LAT = 111_000.0;

    private static final long SIM_TICK_MS = 70L;
    private static final long DEMO_DURATION_MS = 90_000L;

    /*
     * Deterministic sensor-denial scenario.
     *
     * TGT-07 remains present in world truth during this interval, but is
     * withheld from every SensorNode. No TGT-07 measurement therefore enters
     * the Protobuf / UDP / Kafka tracking pipeline.
     */
    private static final String DROPOUT_TARGET_ID = "TGT-07";
    private static final long DROPOUT_START_MS = 15_000L;
    private static final long DROPOUT_END_MS = 17_000L;

    /*
     * Runtime lifecycle window for the two-second denial scenario.
     * At a 70 ms simulation cadence, 45 misses is approximately 3.15 seconds.
     */
    private static final int LIVE_MISSES_TO_COAST = 3;
    private static final int LIVE_MISSES_TO_DROP = 45;

    private record ZoneSpec(
            String zoneId,
            String label,
            String color,
            double centerLng,
            double centerLat,
            double radiusXM,
            double radiusYM,
            double warningBufferM,
            double advisoryBufferM) {
    }

    private static final List<ZoneSpec> ZONE_SPECS =
            List.of(
                    new ZoneSpec(
                            "R-21",
                            "R-21 Restricted Airspace",
                            "#d9535f",
                            -117.055,
                            34.755,
                            1_450.0,
                            1_150.0,
                            300.0,
                            650.0
                    ),
                    new ZoneSpec(
                            "R-33",
                            "R-33 Restricted Airspace",
                            "#d58a32",
                            -117.175,
                            34.805,
                            1_350.0,
                            1_050.0,
                            300.0,
                            625.0
                    ),
                    new ZoneSpec(
                            "R-47",
                            "R-47 Restricted Airspace",
                            "#7b4bc4",
                            -117.090,
                            34.690,
                            1_400.0,
                            1_100.0,
                            325.0,
                            650.0
                    ),
                    new ZoneSpec(
                            "R-58",
                            "R-58 Restricted Airspace",
                            "#4a8f9f",
                            -117.245,
                            34.730,
                            1_350.0,
                            1_050.0,
                            300.0,
                            625.0
                    )
            );

    @Value("${vanguard.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${vanguard.gateway.udp-port:5000}")
    private int udpPort;

    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final List<SensorNode> sensors = new ArrayList<>();
    private final Map<String, MeasurementModel> measurementModels =
            new LinkedHashMap<>();

    private WorldSimulator world;
    private Random rng;
    private long simTimeMs;

    private TrackManager trackManager;
    private GeofenceEngine geofenceEngine;
    private AlertStateMachine alertStateMachine;

    private GatewayMetrics gatewayMetrics;
    private KafkaRawReportProducer gatewayProducer;
    private NettyUdpServer gatewayServer;

    private TrackingPipelineConsumer trackingConsumer;
    private SpatialPipelineConsumer spatialConsumer;

    private Thread trackingThread;
    private Thread spatialThread;

    private ScheduledExecutorService simExecutor;
    private DatagramSocket udpSocket;
    private InetAddress gatewayAddress;

    /*
     * Kafka polls are transport boundaries, not observation boundaries.
     *
     * Reports from one simulator timestamp may be split across consecutive
     * polls. Keep the newest timestamp buffered until a later timestamp
     * arrives, proving that the previous observation cycle is complete.
     *
     * Accessed only by the single tracking-consumer thread.
     */
    private final TreeMap<
            Long,
            Map<String, List<SimpleMatrix>>
            > pendingRawBatches =
            new TreeMap<>();

    public PipelineOrchestrator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        log.info(
                "Starting full Vanguard pipeline (Kafka={}, UDP={})",
                bootstrapServers,
                udpPort
        );

        try {
            ScenarioConfig config = buildDemoScenario();

            world = WorldSimulator.fromConfig(config);
            rng = new Random(config.seed());
            simTimeMs = 0;

            for (ScenarioConfig.SensorSpec spec : config.sensors()) {
                sensors.add(
                        new SensorNode(
                                spec,
                                new Random(rng.nextLong())
                        )
                );

                measurementModels.put(
                        spec.sensorId(),
                        new MeasurementModel(
                                spec.sx(),
                                spec.sy(),
                                spec.sigmaRangeM(),
                                spec.sigmaBearingRad()
                        )
                );
            }

            MotionModel motionModel = new MotionModel(2.0);

            /*
             * Sensor bearing noise is 0.01 rad. At 20-35 km range that can
             * correspond to several hundred meters of Cartesian uncertainty.
             *
             * The spatial grid is only a coarse candidate-pruning structure;
             * the Mahalanobis gate remains the statistical acceptance test.
             */
            DataAssociator associator =
                    new DataAssociator(
                            new MahalanobisGate(9.21),
                            1_000.0
                    );

            trackManager = new TrackManager(
                    associator,
                    motionModel,
                    3,
                    LIVE_MISSES_TO_COAST,
                    LIVE_MISSES_TO_DROP,
                    10_000,
                    62_500
            );

            buildSpatialEngine();

            /*
             * Kafka processing boundaries.
             *
             * These consumers now own tracking and spatial processing.
             * The simulator is no longer allowed to invoke those engines
             * directly.
             */
            trackingConsumer = new TrackingPipelineConsumer(
                    bootstrapServers,
                    "vanguard-tracking-pipeline",
                    this::processRawReports
            );

            spatialConsumer = new SpatialPipelineConsumer(
                    bootstrapServers,
                    "vanguard-spatial-pipeline",
                    this::evaluateFusedTracks
            );

            trackingThread = Thread.ofVirtual()
                    .name("tracking-pipeline")
                    .start(trackingConsumer);

            spatialThread = Thread.ofVirtual()
                    .name("spatial-pipeline")
                    .start(spatialConsumer);

            /*
             * UDP gateway.
             */
            gatewayMetrics = new GatewayMetrics();
            gatewayProducer =
                    new KafkaRawReportProducer(bootstrapServers);

            gatewayServer = new NettyUdpServer(
                    udpPort,
                    gatewayMetrics,
                    new PacketValidator(30_000),
                    new SequenceTracker(),
                    gatewayProducer
            );

            gatewayServer.start();

            udpSocket = new DatagramSocket();
            gatewayAddress = InetAddress.getLoopbackAddress();

            /*
             * Start simulator only after Kafka consumers and UDP gateway
             * have been initialized.
             */
            simExecutor =
                    Executors.newSingleThreadScheduledExecutor(
                            Thread.ofVirtual()
                                    .name("pipeline-sim-", 0)
                                    .factory()
                    );

            simExecutor.scheduleAtFixedRate(
                    this::tick,
                    1_000,
                    SIM_TICK_MS,
                    TimeUnit.MILLISECONDS
            );

            log.info(
                    "Full pipeline running: targets={}, sensors={}, UDP={}, zones={}",
                    world.getTargetCount(),
                    sensors.size(),
                    udpPort,
                    geofenceEngine.getZones().size()
            );

        } catch (Exception e) {
            log.error("Unable to start full pipeline", e);
            stop();
            throw new IllegalStateException(
                    "Unable to start Vanguard full pipeline",
                    e
            );
        }
    }

    /**
     * Simulator boundary.
     *
     * This method is intentionally forbidden from calling TrackManager,
     * GeofenceEngine, or AlertStateMachine directly.
     *
     * It only produces sensor packets and sends them through UDP.
     */
    private void tick() {
        if (!running.get()) {
            return;
        }

        try {
            simTimeMs += SIM_TICK_MS;

            List<TargetModel.TruthRecord> truth =
                    world.truthAt(simTimeMs);

            if (truth.isEmpty()) {
                simTimeMs = 0;
                return;
            }

            /*
             * Remove TGT-07 only from the sensor-visible world during the
             * deterministic blackout. The physical truth trajectory continues.
             */
            List<TargetModel.TruthRecord> observableTruth =
                    isDropoutActive(simTimeMs)
                            ? truth.stream()
                                    .filter(record ->
                                            !DROPOUT_TARGET_ID.equals(
                                                    record.targetId()
                                            ))
                                    .toList()
                            : truth;

            /*
             * The world uses deterministic simulated time for motion.
             * Wire timestamps use wall-clock time because the gateway
             * validates packet freshness.
             */
            long observationTimeMs =
                    System.currentTimeMillis();

            for (SensorNode sensor : sensors) {
                List<SensorNode.RawReport> reports =
                        sensor.observe(
                                observableTruth,
                                observationTimeMs,
                                rng
                        );

                for (SensorNode.RawReport report : reports) {
                    SensorReportProto.SensorReport proto =
                            SensorReportProto.SensorReport
                                    .newBuilder()
                                    .setSensorId(report.sensorId())
                                    .setTimestampMs(
                                            report.timestampMs()
                                    )
                                    .setSensorX(report.sensorX())
                                    .setSensorY(report.sensorY())
                                    .setRange(report.rangeM())
                                    .setAzimuth(
                                            report.azimuthRad()
                                    )
                                    .setSignalStrength(
                                            report.signalStrength()
                                    )
                                    .setSequenceNumber(
                                            report.sequenceNumber()
                                    )
                                    .build();

                    byte[] payload = proto.toByteArray();

                    DatagramPacket packet =
                            new DatagramPacket(
                                    payload,
                                    payload.length,
                                    gatewayAddress,
                                    udpPort
                            );

                    udpSocket.send(packet);
                }
            }

        } catch (Exception e) {
            if (running.get()) {
                log.warn(
                        "Simulator UDP tick failed: {}",
                        e.getMessage()
                );
            }
        }
    }
    private static boolean isDropoutActive(long simulationTimeMs) {
        return simulationTimeMs >= DROPOUT_START_MS
                && simulationTimeMs < DROPOUT_END_MS;
    }


    /**
     * Kafka raw report processor.
     *
     * Called exclusively by TrackingPipelineConsumer.
     */
    private List<TrackingPipelineConsumer.KeyValue> processRawReports(
            List<ConsumerRecord<String, byte[]>> records) {

        /*
         * Kafka poll boundaries are unrelated to simulator observation
         * boundaries. Accumulate reports by wire timestamp across polls.
         *
         * The simulator sends every report for timestamp T before beginning
         * timestamp T+1. Therefore, once T+1 is visible in Kafka, T is a
         * complete observation cycle and can safely be processed.
         */
        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                String csv =
                        new String(
                                record.value(),
                                StandardCharsets.UTF_8
                        );

                String[] p = csv.split(",");

                if (p.length != 8) {
                    log.warn(
                            "Ignoring malformed raw Kafka report: {}",
                            csv
                    );
                    continue;
                }

                String sensorId = p[0];

                long timestampMs =
                        Long.parseLong(p[1]);

                double range =
                        Double.parseDouble(p[4]);

                double azimuth =
                        Double.parseDouble(p[5]);

                if (!measurementModels.containsKey(sensorId)) {
                    log.warn(
                            "Unknown sensor in Kafka report: {}",
                            sensorId
                    );
                    continue;
                }

                SimpleMatrix measurement =
                        new SimpleMatrix(
                                new double[][]{
                                        {range},
                                        {azimuth}
                                }
                        );

                pendingRawBatches
                        .computeIfAbsent(
                                timestampMs,
                                ignored -> new LinkedHashMap<>()
                        )
                        .computeIfAbsent(
                                sensorId,
                                ignored -> new ArrayList<>()
                        )
                        .add(measurement);

            } catch (Exception e) {
                log.warn(
                        "Unable to decode raw Kafka report: {}",
                        e.getMessage()
                );
            }
        }

        /*
         * Always retain the newest observed timestamp.
         *
         * Any older timestamp is now complete because records are published
         * in simulator order through the single raw-report stream.
         */
        if (pendingRawBatches.size() < 2) {
            return List.of();
        }

        long newestTimestampMs =
                pendingRawBatches.lastKey();

        List<Long> readyTimestamps =
                new ArrayList<>();

        for (Long timestampMs : pendingRawBatches.keySet()) {
            if (timestampMs >= newestTimestampMs) {
                break;
            }

            readyTimestamps.add(timestampMs);
        }

        if (readyTimestamps.isEmpty()) {
            return List.of();
        }

        /*
         * Process completed timestamps strictly in chronological order.
         *
         * Sensor order is also deterministic rather than depending on packet
         * arrival order. All measurements belonging to one sensor/timestamp
         * reach DataAssociator together, allowing the global assignment
         * solver to operate on the complete target set.
         */
        for (Long timestampMs : readyTimestamps) {

            Map<String, List<SimpleMatrix>> sensorBatches =
                    pendingRawBatches.remove(timestampMs);

            if (sensorBatches == null) {
                continue;
            }

            for (Map.Entry<String, MeasurementModel> modelEntry
                    : measurementModels.entrySet()) {

                String sensorId =
                        modelEntry.getKey();

                List<SimpleMatrix> measurements =
                        sensorBatches.get(sensorId);

                if (measurements == null
                        || measurements.isEmpty()) {
                    continue;
                }

                trackManager.processObservations(
                        measurements,
                        modelEntry.getValue(),
                        sensorId,
                        timestampMs
                );
            }

            int suppressedDuplicates =
                    trackManager.suppressDuplicateTracks();

            if (suppressedDuplicates > 0) {
                log.debug(
                        "Suppressed {} duplicate track hypotheses at observation {}",
                        suppressedDuplicates,
                        timestampMs
                );
            }
        }

        List<TrackingPipelineConsumer.KeyValue> output =
                new ArrayList<>();

        for (Track track : trackManager.getAllTracks()) {
            try {
                Map<String, Object> fused =
                        encodeFusedTrack(track);

                output.add(
                        new TrackingPipelineConsumer.KeyValue(
                                track.getTrackId(),
                                mapper.writeValueAsBytes(fused)
                        )
                );

            } catch (Exception e) {
                log.warn(
                        "Unable to encode fused track {}: {}",
                        track.getTrackId(),
                        e.getMessage()
                );
            }
        }

        /*
         * Publish DROPPED records once before physically removing
         * those tracks from memory.
         */
        trackManager.pruneDropped();

        return output;
    }
    private Map<String, Object> encodeFusedTrack(Track track) {

        double xM = track.getPx();
        double yM = track.getPy();

        /*
         * Internal metric coordinates are kept explicitly for
         * the spatial consumer.
         *
         * px/py remain WGS84 for API/UI compatibility.
         */
        double lng =
                CENTER_LNG +
                        xM / METERS_PER_DEG_LNG;

        double lat =
                CENTER_LAT +
                        yM / METERS_PER_DEG_LAT;

        Map<String, Object> fused =
                new LinkedHashMap<>();

        fused.put("trackId", track.getTrackId());

        fused.put("xM", xM);
        fused.put("yM", yM);

        fused.put("px", lng);
        fused.put("py", lat);

        fused.put("vx", track.getVx());
        fused.put("vy", track.getVy());

        fused.put(
                "state",
                track.getState().name()
        );

        fused.put(
                "uncertainty",
                track.getPositionUncertainty()
        );

        fused.put(
                "lastUpdateMs",
                track.getLastUpdateMs()
        );

        fused.put(
                "sourceTimestampMs",
                track.getLastUpdateMs()
        );

        fused.put(
                "contributingSensors",
                List.copyOf(
                        track.getContributingSensors()
                )
        );

        SimpleMatrix covariance =
                track.getEkf().getCovariance();

        double pxx = covariance.get(0, 0);
        double pyy = covariance.get(1, 1);
        double pxy = covariance.get(0, 1);

        double angle =
                0.5 * Math.atan2(
                        2 * pxy,
                        pxx - pyy
                );

        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        double majorVariance =
                pxx * cos * cos +
                        2 * pxy * sin * cos +
                        pyy * sin * sin;

        double minorVariance =
                pxx * sin * sin -
                        2 * pxy * sin * cos +
                        pyy * cos * cos;

        double major =
                Math.sqrt(
                        Math.max(0, majorVariance)
                );

        double minor =
                Math.sqrt(
                        Math.max(0, minorVariance)
                );

        fused.put(
                "ellipseMajor",
                major * 2
        );

        fused.put(
                "ellipseMinor",
                minor * 2
        );

        fused.put(
                "ellipseAngle",
                Math.toDegrees(angle)
        );

        return fused;
    }

    /**
     * Called exclusively by SpatialPipelineConsumer.
     */
    private List<SpatialPipelineConsumer.KeyValue> evaluateFusedTracks(
            List<ConsumerRecord<String, byte[]>> records) {

        List<SpatialPipelineConsumer.KeyValue> events =
                new ArrayList<>();

        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> track =
                        mapper.readValue(
                                record.value(),
                                Map.class
                        );

                String trackId =
                        String.valueOf(
                                track.get("trackId")
                        );

                String state =
                        String.valueOf(
                                track.get("state")
                        );

                if ("DROPPED".equals(state)) {
                    alertStateMachine.removeTrack(trackId);
                    continue;
                }

                double xM =
                        toDouble(track.get("xM"));

                double yM =
                        toDouble(track.get("yM"));

                double lng =
                        toDouble(track.get("px"));

                double lat =
                        toDouble(track.get("py"));

                long timestampMs =
                        toLong(track.get("lastUpdateMs"));

                Map<String, ZoneClassification> classifications =
                        geofenceEngine.classify(xM, yM);

                for (Map.Entry<
                        String,
                        ZoneClassification
                        > entry
                        : classifications.entrySet()) {

                    Optional<TrackEvent> transition =
                            alertStateMachine.update(
                                    trackId,
                                    entry.getKey(),
                                    entry.getValue(),
                                    timestampMs,
                                    xM,
                                    yM
                            );

                    if (transition.isEmpty()) {
                        continue;
                    }

                    TrackEvent event =
                            transition.get();

                    String identity =
                            event.trackId() +
                                    "|" +
                                    event.zoneId() +
                                    "|" +
                                    event.type() +
                                    "|" +
                                    timestampMs;

                    String eventId =
                            UUID.nameUUIDFromBytes(
                                            identity.getBytes(
                                                    StandardCharsets.UTF_8
                                            )
                                    )
                                    .toString()
                                    .substring(0, 8);

                    Map<String, Object> json =
                            new LinkedHashMap<>();

                    json.put("eventId", eventId);
                    json.put(
                            "trackId",
                            event.trackId()
                    );
                    json.put(
                            "zoneId",
                            event.zoneId()
                    );
                    json.put(
                            "type",
                            event.type().name()
                    );
                    json.put(
                            "timestampMs",
                            event.timestampMs()
                    );
                    json.put(
                            "previousState",
                            event.previousState().name()
                    );
                    json.put(
                            "newState",
                            event.newState().name()
                    );

                    /*
                     * Browser-facing coordinates.
                     */
                    json.put("px", lng);
                    json.put("py", lat);

                    /*
                     * Internal metric coordinates retained for debugging.
                     */
                    json.put("xM", xM);
                    json.put("yM", yM);

                    events.add(
                            new SpatialPipelineConsumer.KeyValue(
                                    event.trackId() +
                                            "|" +
                                            event.zoneId(),
                                    mapper.writeValueAsBytes(json)
                            )
                    );
                }

            } catch (Exception e) {
                log.warn(
                        "Unable to evaluate fused Kafka track: {}",
                        e.getMessage()
                );
            }
        }

        return events;
    }

    private void buildSpatialEngine() {
        geofenceEngine = new GeofenceEngine();
        alertStateMachine = new AlertStateMachine();

        GeometryFactory geometryFactory =
                new GeometryFactory();

        for (ZoneSpec spec : ZONE_SPECS) {
            geofenceEngine.addZone(
                    new RestrictedZone(
                            spec.zoneId(),
                            ellipsePolygon(
                                    geometryFactory,
                                    spec
                            ),
                            spec.warningBufferM(),
                            spec.advisoryBufferM()
                    )
            );
        }
    }

    private Polygon ellipsePolygon(
            GeometryFactory geometryFactory,
            ZoneSpec spec) {

        double cx =
                (spec.centerLng() - CENTER_LNG) *
                        METERS_PER_DEG_LNG;

        double cy =
                (spec.centerLat() - CENTER_LAT) *
                        METERS_PER_DEG_LAT;

        Coordinate[] coordinates =
                new Coordinate[65];

        for (int i = 0; i < 64; i++) {
            double angle =
                    (i / 64.0) *
                            Math.PI *
                            2;

            coordinates[i] =
                    new Coordinate(
                            cx +
                                    Math.cos(angle) *
                                            spec.radiusXM(),
                            cy +
                                    Math.sin(angle) *
                                            spec.radiusYM()
                    );
        }

        coordinates[64] =
                coordinates[0];

        return geometryFactory.createPolygon(
                coordinates
        );
    }

    /**
     * Browser-facing geofence configuration.
     *
     * The UI receives the exact core, warning, and advisory geometries used by
     * the spatial engine instead of maintaining decorative frontend polygons.
     */
    public List<Map<String, Object>> getZoneDefinitions() {
        if (geofenceEngine == null) {
            return List.of();
        }

        List<Map<String, Object>> definitions =
                new ArrayList<>();

        for (RestrictedZone zone : geofenceEngine.getZones()) {
            ZoneSpec spec =
                    findZoneSpec(
                            zone.getZoneId()
                    );

            if (spec == null) {
                continue;
            }

            Map<String, Object> definition =
                    new LinkedHashMap<>();

            definition.put(
                    "zoneId",
                    spec.zoneId()
            );

            definition.put(
                    "label",
                    spec.label()
            );

            definition.put(
                    "color",
                    spec.color()
            );

            definition.put(
                    "center",
                    List.of(
                            spec.centerLng(),
                            spec.centerLat()
                    )
            );

            definition.put(
                    "warningBufferM",
                    spec.warningBufferM()
            );

            definition.put(
                    "advisoryBufferM",
                    spec.advisoryBufferM()
            );

            definition.put(
                    "core",
                    toWgs84Ring(
                            zone.getPolygon()
                    )
            );

            definition.put(
                    "warning",
                    toWgs84Ring(
                            zone.getWarningBuffer()
                    )
            );

            definition.put(
                    "advisory",
                    toWgs84Ring(
                            zone.getAdvisoryBuffer()
                    )
            );

            definitions.add(
                    definition
            );
        }

        return List.copyOf(
                definitions
        );
    }

    private ZoneSpec findZoneSpec(
            String zoneId) {

        for (ZoneSpec spec : ZONE_SPECS) {
            if (spec.zoneId().equals(zoneId)) {
                return spec;
            }
        }

        return null;
    }

    private List<List<Double>> toWgs84Ring(
            org.locationtech.jts.geom.Geometry geometry) {

        Coordinate[] coordinates;

        if (geometry instanceof Polygon polygon) {
            coordinates =
                    polygon
                            .getExteriorRing()
                            .getCoordinates();
        } else {
            coordinates =
                    geometry.getCoordinates();
        }

        List<List<Double>> ring =
                new ArrayList<>(
                        coordinates.length
                );

        for (Coordinate coordinate : coordinates) {
            ring.add(
                    List.of(
                            CENTER_LNG +
                                    coordinate.x /
                                            METERS_PER_DEG_LNG,
                            CENTER_LAT +
                                    coordinate.y /
                                            METERS_PER_DEG_LAT
                    )
            );
        }

        return List.copyOf(
                ring
        );
    }

    private ScenarioConfig buildDemoScenario() {


        List<ScenarioConfig.TargetSpec> targets =
                List.of(
                        new ScenarioConfig.TargetSpec(
                                "TGT-01", 0,
                                -15_000, 2_000,
                                205, 8,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-02", 0,
                                14_000, 6_000,
                                -195, -18,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-03", 0,
                                -7_000, -8_500,
                                55, 190,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-04", 0,
                                -12_000, 8_000,
                                175, -70,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-05", 0,
                                11_500, -7_500,
                                -165, 135,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-06", 0,
                                -2_500, -9_000,
                                25, 185,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-07", 0,
                                13_500, 1_000,
                                -190, 20,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-08", 0,
                                -16_000, -5_000,
                                200, 75,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-09", 0,
                                6_000, 9_500,
                                -110, -175,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-10", 0,
                                -4_000, 10_000,
                                95, -170,
                                List.of(ScenarioConfig.SegmentSpec.straight(90))
                        )
                );
        List<ScenarioConfig.SensorSpec> sensorSpecs =
                List.of(
                        new ScenarioConfig.SensorSpec(
                                "SSA-01",
                                (-117.35 - CENTER_LNG) *
                                        METERS_PER_DEG_LNG,
                                (34.79 - CENTER_LAT) *
                                        METERS_PER_DEG_LAT,
                                50,
                                0.01,
                                5,
                                0.001,
                                100,
                                0.0
                        ),

                        new ScenarioConfig.SensorSpec(
                                "SSB-02",
                                (-117.38 - CENTER_LNG) *
                                        METERS_PER_DEG_LNG,
                                (34.71 - CENTER_LAT) *
                                        METERS_PER_DEG_LAT,
                                50,
                                0.01,
                                5,
                                0.001,
                                100,
                                0.0
                        ),

                        new ScenarioConfig.SensorSpec(
                                "SSC-03",
                                (-117.05 - CENTER_LNG) *
                                        METERS_PER_DEG_LNG,
                                (34.68 - CENTER_LAT) *
                                        METERS_PER_DEG_LAT,
                                50,
                                0.01,
                                5,
                                0.001,
                                100,
                                0.0
                        )
                );

        return new ScenarioConfig(
                "demo-live",
                42L,
                DEMO_DURATION_MS,
                targets,
                sensorSpecs,
                new ScenarioConfig.ImpairmentSpec(
                        0.0,
                        0.0,
                        0,
                        0,
                        false
                )
        );
    }

    public long getGatewayPacketsAccepted() {
        return gatewayMetrics == null
                ? 0
                : gatewayMetrics.getPacketsAccepted();
    }

    public long getGatewayPacketsDropped() {
        return gatewayMetrics == null
                ? 0
                : gatewayMetrics.getPacketsDropped();
    }

    public long getGatewayPacketsReceived() {
        return gatewayMetrics == null
                ? 0
                : gatewayMetrics.getPacketsReceived();
    }

    @PreDestroy
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (simExecutor != null) {
            simExecutor.shutdownNow();
        }

        if (udpSocket != null) {
            udpSocket.close();
        }

        if (gatewayServer != null) {
            gatewayServer.stop();
        }

        if (trackingConsumer != null) {
            trackingConsumer.stop();
        }

        if (spatialConsumer != null) {
            spatialConsumer.stop();
        }

        joinQuietly(trackingThread);
        joinQuietly(spatialThread);

        if (gatewayProducer != null) {
            gatewayProducer.close();
        }

        log.info(
                "Full pipeline stopped at simTime={}ms",
                simTimeMs
        );
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }

        try {
            thread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return Double.parseDouble(
                String.valueOf(value)
        );
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }

        return Long.parseLong(
                String.valueOf(value)
        );
    }
}