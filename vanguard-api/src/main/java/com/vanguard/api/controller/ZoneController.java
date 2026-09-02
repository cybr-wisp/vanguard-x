package com.vanguard.api.controller;

import com.vanguard.api.pipeline.PipelineOrchestrator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Backend-owned geofence configuration for the tactical map.
 *
 * The browser receives the exact spatial-engine geometries rather than
 * maintaining decorative or duplicated frontend zone definitions.
 */
@RestController
@RequestMapping("/api/zones")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(
        name = "vanguard.demo.enabled",
        havingValue = "false"
)
public class ZoneController {

    private final PipelineOrchestrator pipeline;

    public ZoneController(
            PipelineOrchestrator pipeline) {

        this.pipeline = pipeline;
    }

    @GetMapping
    public List<Map<String, Object>> getZones() {
        return pipeline.getZoneDefinitions();
    }
}
