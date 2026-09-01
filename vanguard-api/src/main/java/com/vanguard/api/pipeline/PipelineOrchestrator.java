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

            DataAssociator associator =
                    new DataAssociator(
                            new MahalanobisGate(9.21)
                    );

            trackManager = new TrackManager(
                    associator,
                    motionModel,
                    3,
                    3,
                    8,
                    10_000,
                    100
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
                    70,
                    TimeUnit.MILLISECONDS
            );

            log.info(
                    "Full pipeline running: targets={}, sensors={}, UDP={}, zone=R-21",
                    world.getTargetCount(),
                    sensors.size(),
                    udpPort
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
            simTimeMs += 100;

            List<TargetModel.TruthRecord> truth =
                    world.truthAt(simTimeMs);

            if (truth.isEmpty()) {
                simTimeMs = 0;
                return;
            }

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
                                truth,
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

    /**
     * Kafka raw report processor.
     *
     * Called exclusively by TrackingPipelineConsumer.
     */
    private List<TrackingPipelineConsumer.KeyValue> processRawReports(
            List<ConsumerRecord<String, byte[]>> records) {

        /*
         * Group by observation timestamp first, then sensor.
         *
         * TrackManager expects one sensor batch representing one
         * observation cycle.
         */
        TreeMap<Long, Map<String, List<SimpleMatrix>>> batches =
                new TreeMap<>();

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

                batches
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

        for (Map.Entry<
                Long,
                Map<String, List<SimpleMatrix>>
                > timeEntry : batches.entrySet()) {

            long timestampMs = timeEntry.getKey();

            for (Map.Entry<
                    String,
                    List<SimpleMatrix>
                    > sensorEntry
                    : timeEntry.getValue().entrySet()) {

                String sensorId =
                        sensorEntry.getKey();

                MeasurementModel measurementModel =
                        measurementModels.get(sensorId);

                if (measurementModel == null) {
                    continue;
                }

                trackManager.processObservations(
                        sensorEntry.getValue(),
                        measurementModel,
                        sensorId,
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

        double cx =
                (-117.05 - CENTER_LNG) *
                        METERS_PER_DEG_LNG;

        double cy =
                (34.755 - CENTER_LAT) *
                        METERS_PER_DEG_LAT;

        double rx =
                0.06 * METERS_PER_DEG_LNG;

        double ry =
                0.04 * METERS_PER_DEG_LAT;

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
                                            rx,
                            cy +
                                    Math.sin(angle) *
                                            ry
                    );
        }

        coordinates[64] =
                coordinates[0];

        Polygon polygon =
                geometryFactory.createPolygon(
                        coordinates
                );

        geofenceEngine.addZone(
                new RestrictedZone(
                        "R-21",
                        polygon,
                        500,
                        1_000
                )
        );
    }

    private ScenarioConfig buildDemoScenario() {

        List<ScenarioConfig.TargetSpec> targets =
                List.of(
                        new ScenarioConfig.TargetSpec(
                                "TGT-01",
                                0,
                                (-0.15) * METERS_PER_DEG_LNG,
                                0.05 * METERS_PER_DEG_LAT,
                                25,
                                -5,
                                List.of(
                                        ScenarioConfig.SegmentSpec.straight(15),
                                        ScenarioConfig.SegmentSpec.turn(20, 0.02),
                                        ScenarioConfig.SegmentSpec.straight(15)
                                )
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-02",
                                0,
                                0.12 * METERS_PER_DEG_LNG,
                                0.06 * METERS_PER_DEG_LAT,
                                -20,
                                -10,
                                List.of(
                                        ScenarioConfig.SegmentSpec.straight(20),
                                        ScenarioConfig.SegmentSpec.turn(15, -0.015),
                                        ScenarioConfig.SegmentSpec.straight(15)
                                )
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-03",
                                0,
                                -0.05 * METERS_PER_DEG_LNG,
                                -0.06 * METERS_PER_DEG_LAT,
                                8,
                                18,
                                List.of(
                                        ScenarioConfig.SegmentSpec.straight(15),
                                        ScenarioConfig.SegmentSpec.turn(10, 0.03),
                                        ScenarioConfig.SegmentSpec.straight(25)
                                )
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-04",
                                2_000,
                                0.08 * METERS_PER_DEG_LNG,
                                0.02 * METERS_PER_DEG_LAT,
                                -5,
                                3,
                                List.of(
                                        ScenarioConfig.SegmentSpec.straight(20),
                                        ScenarioConfig.SegmentSpec.turn(25, -0.01),
                                        ScenarioConfig.SegmentSpec.straight(5)
                                )
                        ),

                        new ScenarioConfig.TargetSpec(
                                "TGT-05",
                                1_000,
                                -0.10 * METERS_PER_DEG_LNG,
                                0.08 * METERS_PER_DEG_LAT,
                                30,
                                -15,
                                List.of(
                                        ScenarioConfig.SegmentSpec.straight(10),
                                        ScenarioConfig.SegmentSpec.turn(15, 0.025),
                                        ScenarioConfig.SegmentSpec.straight(25)
                                )
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
                                0.02
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
                                0.02
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
                                0.02
                        )
                );

        return new ScenarioConfig(
                "demo-live",
                42L,
                50_000L,
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