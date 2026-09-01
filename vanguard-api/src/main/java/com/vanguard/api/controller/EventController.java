package com.vanguard.api.controller;

import com.vanguard.api.repository.EventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST endpoints for track events.
 *
 * GET /api/events            - recent events (default last 50)
 * GET /api/events/{eventId}  - single event details
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventRepository eventRepo;

    public EventController(EventRepository eventRepo) {
        this.eventRepo = eventRepo;
    }

    @GetMapping
    public List<String> getRecentEvents(@RequestParam(name = "count", defaultValue = "50") int count) {
        return eventRepo.getRecentEvents(Math.min(count, 500));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<Map<Object, Object>> getEvent(@PathVariable("eventId") String eventId) {
        Map<Object, Object> event = eventRepo.getEvent(eventId);
        if (event.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(event);
    }
}
