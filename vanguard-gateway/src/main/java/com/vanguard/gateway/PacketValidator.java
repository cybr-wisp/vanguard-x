package com.vanguard.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates decoded SensorReport fields before the report enters the pipeline.
 * Returns a list of rejection reasons (empty = valid).
 *
 * Validation rules:
 *   - sensor_id must be non-blank
 *   - timestamp_ms must be positive and within a configurable staleness window
 *   - range must be non-negative
 *   - azimuth must be within [-2pi, 2pi] (generous; normalization happens downstream)
 *   - sequence_number must be non-negative
 */
public class PacketValidator {

    private final long maxStalenessMs;

    /**
     * @param maxStalenessMs maximum age of a report before it is rejected
     *                       as stale. Use Long.MAX_VALUE to disable.
     */
    public PacketValidator(long maxStalenessMs) {
        this.maxStalenessMs = maxStalenessMs;
    }

    /** Default: 30-second staleness window. */
    public PacketValidator() {
        this(30_000L);
    }

    /**
     * Validate a decoded report. The input is a simple record to decouple
     * validation from the Protobuf generated class.
     *
     * @return empty list if valid; list of human-readable rejection reasons otherwise
     */
    public List<String> validate(DecodedReport report, long currentTimeMs) {
        List<String> reasons = null; // lazy alloc

        if (report.sensorId() == null || report.sensorId().isBlank()) {
            reasons = addReason(reasons, "sensor_id is blank");
        }

        if (report.timestampMs() <= 0) {
            reasons = addReason(reasons, "timestamp_ms is non-positive: " + report.timestampMs());
        } else if (maxStalenessMs < Long.MAX_VALUE) {
            long age = currentTimeMs - report.timestampMs();
            if (age > maxStalenessMs) {
                reasons = addReason(reasons, "report is stale by %d ms (limit %d)".formatted(age, maxStalenessMs));
            }
            if (age < -5_000) {
                // More than 5 seconds in the future suggests clock skew
                reasons = addReason(reasons, "report timestamp is %d ms in the future".formatted(-age));
            }
        }

        if (report.range() < 0) {
            reasons = addReason(reasons, "range is negative: " + report.range());
        }

        if (Double.isNaN(report.azimuth()) || Double.isInfinite(report.azimuth())) {
            reasons = addReason(reasons, "azimuth is NaN or Inf");
        } else if (Math.abs(report.azimuth()) > 2 * Math.PI) {
            reasons = addReason(reasons, "azimuth out of [-2pi, 2pi]: " + report.azimuth());
        }

        if (report.sequenceNumber() < 0) {
            reasons = addReason(reasons, "sequence_number is negative: " + report.sequenceNumber());
        }

        return reasons == null ? Collections.emptyList() : reasons;
    }

    private static List<String> addReason(List<String> list, String reason) {
        if (list == null) list = new ArrayList<>(2);
        list.add(reason);
        return list;
    }

    /**
     * Decoded report: a plain record decoupled from Protobuf generated classes.
     * The ProtobufDecoder maps SensorReportProto.SensorReport to this.
     */
    public record DecodedReport(
            String sensorId,
            long   timestampMs,
            double sensorX,
            double sensorY,
            double range,
            double azimuth,
            double signalStrength,
            long   sequenceNumber
    ) {}
}
