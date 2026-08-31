package com.vanguard.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Bounded in-memory sink backed by an ArrayBlockingQueue. When the queue is
 * full, offer() returns false (backpressure) rather than blocking.
 * This is the correct UDP behavior: overload becomes drops, not retry storms.
 */
public class InMemoryReportSink implements RawReportSink {

    private final BlockingQueue<PacketValidator.DecodedReport> queue;

    public InMemoryReportSink(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public boolean offer(PacketValidator.DecodedReport report) {
        return queue.offer(report);
    }

    /** Drain all queued reports (testing). */
    public List<PacketValidator.DecodedReport> drain() {
        List<PacketValidator.DecodedReport> out = new ArrayList<>();
        queue.drainTo(out);
        return out;
    }

    public int size() { return queue.size(); }
    public int remainingCapacity() { return queue.remainingCapacity(); }
}
