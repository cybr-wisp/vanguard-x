package com.vanguard.gateway;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Netty-based UDP server that receives sensor report datagrams. The pipeline
 * on the event loop is strictly non-blocking:
 *
 *   1. Extract bytes from DatagramPacket
 *   2. Protobuf decode (CPU-light, no allocation beyond the proto object)
 *   3. Validate fields
 *   4. Sequence check (dedup)
 *   5. Offer to bounded sink (non-blocking; drops if full)
 *
 * No blocking I/O, no heavy computation, no synchronized locks on the
 * event loop. The bounded sink decouples the event loop from downstream
 * processing.
 */
public class NettyUdpServer {

    private static final Logger log = LoggerFactory.getLogger(NettyUdpServer.class);

    private final int port;
    private final GatewayMetrics metrics;
    private final ProtobufDecoder decoder;
    private final PacketValidator validator;
    private final SequenceTracker sequenceTracker;
    private final RawReportSink sink;

    private EventLoopGroup group;
    private Channel channel;

    public NettyUdpServer(int port, GatewayMetrics metrics, PacketValidator validator,
                          SequenceTracker sequenceTracker, RawReportSink sink) {
        this.port = port;
        this.metrics = metrics;
        this.decoder = new ProtobufDecoder(metrics);
        this.validator = validator;
        this.sequenceTracker = sequenceTracker;
        this.sink = sink;
    }

    /** Start listening. Call from the application main thread. */
    public void start() throws InterruptedException {
        group = new NioEventLoopGroup(1); // single event loop for UDP

        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024) // 4 MB receive buffer
                .handler(new GatewayHandler());

        channel = b.bind(port).sync().channel();
        log.info("UDP gateway listening on port {}", port);
    }

    /** Shut down gracefully. */
    public void stop() {
        if (channel != null) channel.close();
        if (group != null) group.shutdownGracefully();
        log.info("UDP gateway stopped. {}", metrics.summary());
    }

    public GatewayMetrics getMetrics() { return metrics; }

    /**
     * The inbound handler. Runs entirely on the Netty event loop -- no blocking.
     */
    private class GatewayHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            ByteBuf buf = packet.content();
            int readableBytes = buf.readableBytes();
            metrics.recordReceived(readableBytes);

            // 1. Extract bytes
            byte[] data = new byte[readableBytes];
            buf.readBytes(data);

            // 2. Decode protobuf
            PacketValidator.DecodedReport report = decoder.decode(data);
            if (report == null) return; // malformed, already counted

            // 3. Validate fields
            long now = System.currentTimeMillis();
            List<String> rejections = validator.validate(report, now);
            if (!rejections.isEmpty()) {
                metrics.recordMalformed();
                log.debug("Rejected report from {}: {}", report.sensorId(), rejections);
                return;
            }

            // 4. Sequence check
            SequenceTracker.SequenceVerdict verdict =
                    sequenceTracker.check(report.sensorId(), report.sequenceNumber());

            if (verdict == SequenceTracker.SequenceVerdict.DUPLICATE) {
                metrics.recordDuplicate();
                log.trace("Duplicate seq {} from {}", report.sequenceNumber(), report.sensorId());
                return;
            }

            if (verdict == SequenceTracker.SequenceVerdict.GAP_THEN_ACCEPT) {
                log.debug("Sequence gap detected for {} at seq {}", report.sensorId(), report.sequenceNumber());
            }

            // 5. Offer to sink (non-blocking)
            boolean accepted = sink.offer(report);
            if (accepted) {
                metrics.recordAccepted();
            } else {
                metrics.recordDropped();
                log.trace("Backpressure drop for {} seq {}", report.sensorId(), report.sequenceNumber());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Gateway handler error", cause);
            // Do not close the channel -- UDP servers should keep running
        }
    }
}
