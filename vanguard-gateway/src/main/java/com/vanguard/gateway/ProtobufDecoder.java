package com.vanguard.gateway;

import com.google.protobuf.InvalidProtocolBufferException;
import com.vanguard.protocol.SensorReportProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes a raw byte payload (from a UDP datagram) into a {@link PacketValidator.DecodedReport}.
 * Returns null on malformed payloads and increments the malformed counter.
 *
 * This is intentionally not a Netty ChannelHandler so it can be unit-tested
 * without a Netty pipeline. The NettyUdpServer calls it from inside the handler.
 */
public class ProtobufDecoder {

    private static final Logger log = LoggerFactory.getLogger(ProtobufDecoder.class);

    private final GatewayMetrics metrics;

    public ProtobufDecoder(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Attempt to decode bytes into a DecodedReport.
     *
     * @param data raw Protobuf bytes from the UDP datagram
     * @return decoded report, or null if the payload is malformed
     */
    public PacketValidator.DecodedReport decode(byte[] data) {
        if (data == null || data.length == 0) {
            metrics.recordMalformed();
            log.debug("Empty payload");
            return null;
        }

        try {
            SensorReportProto.SensorReport proto = SensorReportProto.SensorReport.parseFrom(data);

            return new PacketValidator.DecodedReport(
                    proto.getSensorId(),
                    proto.getTimestampMs(),
                    proto.getSensorX(),
                    proto.getSensorY(),
                    proto.getRange(),
                    proto.getAzimuth(),
                    proto.getSignalStrength(),
                    proto.getSequenceNumber()
            );
        } catch (InvalidProtocolBufferException e) {
            metrics.recordMalformed();
            log.debug("Malformed protobuf: {}", e.getMessage());
            return null;
        }
    }
}
