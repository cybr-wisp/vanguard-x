# Deterministic Replay

## Purpose

Replay allows a recorded sensor report stream to be replayed through
the tracking pipeline to produce materially identical output. This enables:

1. Regression testing against known-good baselines
2. Debugging association and lifecycle edge cases
3. Benchmark reproducibility across hardware

## How it works

**Recording:** During a live run, the `ScenarioRecorder` captures every
validated sensor report with its original timestamp and sequence number.
Reports are written to a file sorted by event time.

**Replay:** The recorded file is fed back into the pipeline in event-time
order. The tracker processes each report as if it arrived live.

**Idempotency:** Duplicate reports (same sensor_id + sequence_number)
are rejected by the `SequenceTracker`, just as they would be in a live
run. This makes at-least-once delivery from Kafka safe: replayed
duplicates produce no side effects.

## File format

One report per line, pipe-delimited:

    sensorId|timestampMs|sensorX|sensorY|range|azimuth|signalStrength|seqNum

Example:

    SENSOR-ALPHA|15000|−2000.000000|0.000000|3522.145600|1.176320|8.340000|42

## Event-time ordering

Reports are processed in event-time (observation timestamp) order, not
arrival order. This ensures that the tracker sees the same temporal
sequence regardless of whether the reports were jittered, reordered,
or batched during the original run.

Late arrivals that fall outside the bounded reordering window are dropped,
matching the live behavior.

## What "materially identical" means

Floating-point arithmetic is not associative, so bit-exact reproduction
across different hardware is not guaranteed. "Materially identical" means:

- The same tracks are created and dropped
- Track lifecycle transitions occur at the same timestamps
- Position RMSE differences are within 1e-6 of the original run
- The same zone events are emitted in the same order

## Limitations

Replay does not exercise the Netty UDP path or network impairments.
It feeds reports directly into the tracking pipeline at the Kafka
consumer boundary.
