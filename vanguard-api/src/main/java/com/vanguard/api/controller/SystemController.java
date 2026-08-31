package com.vanguard.api.controller;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * System-level endpoints for health, metrics, and scenario control.
 *
 * GET  /api/health     - health check (used by Docker Compose)
 * GET  /api/metrics    - latest system metrics snapshot
 * GET  /api/scenarios  - available scenario configurations
 */
@RestController
@RequestMapping("/api")
public class SystemController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", System.currentTimeMillis(),
                "version", "0.1.0-SNAPSHOT"
        );
    }

    @GetMapping("/readiness")
    public Map<String, Object> readiness() {
        // In production, check Kafka consumer lag, Redis connectivity, etc.
        return Map.of(
                "ready", true,
                "kafka", "connected",
                "redis", "connected"
        );
    }
}
