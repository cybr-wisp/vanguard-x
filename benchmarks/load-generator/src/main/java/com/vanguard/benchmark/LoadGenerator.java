package com.vanguard.benchmark;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Load generator for formal performance campaigns. Sends synthetic
 * Protobuf-encoded sensor reports over UDP at a configurable rate.
 *
 * Supports the benchmark matrix from the build guide:
 *   Baseline:  100 targets,  1k/s
 *   Moderate:  500 targets,  5k/s
 *   Target:  1,000 targets, 10k/s
 *   Stress:  2,000+ targets, 20k/s+
 *
 * Records throughput, drops, and send-side latency for each run.
 */
public class LoadGenerator {

    public record LoadProfile(
            String name,
            int targetCount,
            int reportsPerSecond,
            int durationSeconds,
            int sensorCount
    ) {}

    public record LoadResult(
            String profileName,
            long totalSent,
            long totalFailed,
            double actualRatePerSec,
            double sendP50Us,
            double sendP95Us,
            double sendP99Us,
            long durationMs
    ) {
        @Override
        public String toString() {
            return "%s: sent=%d failed=%d rate=%.0f/s p50=%.0fus p95=%.0fus p99=%.0fus duration=%dms"
                    .formatted(profileName, totalSent, totalFailed, actualRatePerSec,
                            sendP50Us, sendP95Us, sendP99Us, durationMs);
        }
    }

    /**
     * Standard benchmark matrix from the build guide.
     */
    public static LoadProfile[] standardMatrix() {
        return new LoadProfile[]{
                new LoadProfile("baseline",  100,  1000, 30, 3),
                new LoadProfile("moderate",  500,  5000, 30, 3),
                new LoadProfile("target",   1000, 10000, 30, 3),
                new LoadProfile("stress",   2000, 20000, 30, 3),
        };
    }

    /**
     * Run a single load profile against a target host.
     */
    public static LoadResult run(LoadProfile profile, String host, int port)
            throws Exception {

        DatagramSocket socket = new DatagramSocket();
        InetAddress addr = InetAddress.getByName(host);
        LongAdder sent = new LongAdder();
        LongAdder failed = new LongAdder();

        long intervalNs = 1_000_000_000L / profile.reportsPerSecond;
        int totalReports = profile.reportsPerSecond * profile.durationSeconds;
        long[] latencies = new long[totalReports];

        Instant start = Instant.now();
        long nextSendNs = System.nanoTime();

        for (int i = 0; i < totalReports; i++) {
            // Rate limiting: busy-wait until the next send time
            while (System.nanoTime() < nextSendNs) {
                Thread.onSpinWait();
            }

            long t0 = System.nanoTime();
            try {
                byte[] payload = generateReport(i, profile);
                DatagramPacket packet = new DatagramPacket(payload, payload.length, addr, port);
                socket.send(packet);
                sent.increment();
            } catch (Exception e) {
                failed.increment();
            }
            latencies[i] = System.nanoTime() - t0;

            nextSendNs += intervalNs;
        }

        Duration elapsed = Duration.between(start, Instant.now());
        socket.close();

        // Compute send-side percentiles
        java.util.Arrays.sort(latencies);
        double p50 = latencies[(int)(totalReports * 0.50)] / 1000.0;
        double p95 = latencies[(int)(totalReports * 0.95)] / 1000.0;
        double p99 = latencies[(int)(totalReports * 0.99)] / 1000.0;

        return new LoadResult(
                profile.name, sent.sum(), failed.sum(),
                sent.sum() / (elapsed.toMillis() / 1000.0),
                p50, p95, p99, elapsed.toMillis());
    }

    /**
     * Generate a synthetic report payload. In production this would
     * be Protobuf-encoded; here we use a compact binary format.
     */
    private static byte[] generateReport(int index, LoadProfile profile) {
        int sensorIdx = index % profile.sensorCount;
        int targetIdx = index % profile.targetCount;
        String csv = "SENSOR-%d,%d,%.2f,%.2f,%.4f,%.4f,%.2f,%d".formatted(
                sensorIdx, System.currentTimeMillis(),
                sensorIdx * 1000.0, 0.0,
                500.0 + targetIdx * 10.0, Math.random() * Math.PI,
                5.0 + Math.random(), (long) index);
        return csv.getBytes();
    }
}
