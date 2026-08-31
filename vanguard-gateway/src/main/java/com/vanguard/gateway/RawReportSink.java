package com.vanguard.gateway;

/**
 * Downstream sink for validated reports. The gateway pushes accepted reports
 * here after decoding, validation, and dedup.
 *
 * Implementations:
 *   - InMemoryReportSink: bounded queue for testing and benchmarks
 *   - KafkaRawReportProducer: publishes to sensor-reports.raw (Day 13)
 */
public interface RawReportSink {

    /**
     * Offer a validated report to the sink.
     *
     * @return true if accepted, false if the sink is full (backpressure).
     *         The gateway increments the dropped counter on false.
     */
    boolean offer(PacketValidator.DecodedReport report);
}
