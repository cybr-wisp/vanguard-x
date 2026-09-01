package com.vanguard.api.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket configuration for live streaming. Three endpoints:
 *   /ws/tracks  - fused track position updates
 *   /ws/events  - track events (zone transitions)
 *   /ws/health  - system health metrics
 *
 * Clients subscribe by connecting to the endpoint. The backend pushes
 * updates at a throttled rate independent from the internal processing rate.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TrackStreamHandler trackHandler;
    private final EventStreamHandler eventHandler;
    private final HealthStreamHandler healthHandler;

    public WebSocketConfig(TrackStreamHandler trackHandler,
                           EventStreamHandler eventHandler,
                           HealthStreamHandler healthHandler) {
        this.trackHandler = trackHandler;
        this.eventHandler = eventHandler;
        this.healthHandler = healthHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(trackHandler, "/ws/tracks").setAllowedOrigins("*");
        registry.addHandler(eventHandler, "/ws/events").setAllowedOrigins("*");
        registry.addHandler(healthHandler, "/ws/health").setAllowedOrigins("*");
    }

    /**
     * Base handler that manages connected sessions and broadcasts messages.
     */
    static abstract class BroadcastHandler extends TextWebSocketHandler {
        protected final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            sessions.add(session);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            sessions.remove(session);
        }

        /**
         * Broadcast a message to all connected clients. Dead sessions are
         * removed immediately to prevent poisoning future broadcasts.
         */
        public void broadcast(String message) {
            TextMessage msg = new TextMessage(message);
            for (WebSocketSession session : sessions) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(msg);
                    }
                } catch (Exception e) {
                    sessions.remove(session);
                    try { session.close(); } catch (Exception ignored) {}
                }
            }
        }

        public int getConnectionCount() { return sessions.size(); }
    }

    @Component
    public static class TrackStreamHandler extends BroadcastHandler {}

    @Component
    public static class EventStreamHandler extends BroadcastHandler {}

    @Component
    public static class HealthStreamHandler extends BroadcastHandler {}
}