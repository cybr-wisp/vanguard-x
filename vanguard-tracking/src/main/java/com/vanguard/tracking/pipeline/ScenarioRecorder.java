package com.vanguard.tracking.pipeline;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Records raw sensor reports to a file during a live run, and replays them
 * deterministically. The same recorded input should produce materially
 * identical fused-track/event output across repeated runs.
 *
 * File format: one report per line, pipe-delimited:
 *   sensorId|timestampMs|sensorX|sensorY|range|azimuth|signalStrength|seqNum
 *
 * Sorted by timestampMs on write for event-time replay ordering.
 */
public class ScenarioRecorder {

    public record RecordedReport(
            String sensorId, long timestampMs,
            double sensorX, double sensorY,
            double range, double azimuth,
            double signalStrength, long sequenceNumber
    ) {
        public String serialize() {
            return "%s|%d|%.6f|%.6f|%.6f|%.6f|%.6f|%d".formatted(
                    sensorId, timestampMs, sensorX, sensorY,
                    range, azimuth, signalStrength, sequenceNumber);
        }

        public static RecordedReport deserialize(String line) {
            String[] p = line.split("\\|");
            return new RecordedReport(
                    p[0], Long.parseLong(p[1]),
                    Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Double.parseDouble(p[4]), Double.parseDouble(p[5]),
                    Double.parseDouble(p[6]), Long.parseLong(p[7]));
        }
    }

    private final List<RecordedReport> buffer = new ArrayList<>();

    /** Record a report during a live run. */
    public void record(RecordedReport report) {
        buffer.add(report);
    }

    /** Write all recorded reports to a file, sorted by event time. */
    public void writeTo(Path path) throws IOException {
        buffer.sort(Comparator.comparingLong(RecordedReport::timestampMs));
        try (BufferedWriter w = Files.newBufferedWriter(path)) {
            for (RecordedReport r : buffer) {
                w.write(r.serialize());
                w.newLine();
            }
        }
    }

    /** Load a recorded scenario for replay. */
    public static List<RecordedReport> loadFrom(Path path) throws IOException {
        List<RecordedReport> reports = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    reports.add(RecordedReport.deserialize(line));
                }
            }
        }
        return reports;
    }

    public int size() { return buffer.size(); }
    public void clear() { buffer.clear(); }
}
