package com.vanguard.gateway;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe gateway metrics. Counters use LongAdder for low-contention
 * concurrent increments from the Netty event loop.
 *
 * These become Micrometer gauges/counters on Day 20 (observability).
 */
public class GatewayMetrics {

    private final LongAdder packetsReceived   = new LongAdder();
    private final LongAdder packetsAccepted   = new LongAdder();
    private final LongAdder packetsMalformed  = new LongAdder();
    private final LongAdder packetsDuplicate  = new LongAdder();
    private final LongAdder packetsDropped    = new LongAdder(); // backpressure drops
    private final LongAdder bytesReceived     = new LongAdder();

    public void recordReceived(int bytes) { packetsReceived.increment(); bytesReceived.add(bytes); }
    public void recordAccepted()          { packetsAccepted.increment(); }
    public void recordMalformed()         { packetsMalformed.increment(); }
    public void recordDuplicate()         { packetsDuplicate.increment(); }
    public void recordDropped()           { packetsDropped.increment(); }

    public long getPacketsReceived()  { return packetsReceived.sum(); }
    public long getPacketsAccepted()  { return packetsAccepted.sum(); }
    public long getPacketsMalformed() { return packetsMalformed.sum(); }
    public long getPacketsDuplicate() { return packetsDuplicate.sum(); }
    public long getPacketsDropped()   { return packetsDropped.sum(); }
    public long getBytesReceived()    { return bytesReceived.sum(); }

    /** Snapshot for logging / test assertions. */
    public String summary() {
        return "received=%d accepted=%d malformed=%d duplicate=%d dropped=%d bytes=%d".formatted(
                getPacketsReceived(), getPacketsAccepted(), getPacketsMalformed(),
                getPacketsDuplicate(), getPacketsDropped(), getBytesReceived());
    }
}
