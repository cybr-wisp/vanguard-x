package com.vanguard.api.controller;

import com.vanguard.api.repository.TrackRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST endpoints for live track state.
 *
 * GET /api/tracks           - all active tracks
 * GET /api/tracks/{id}      - single track details
 * GET /api/tracks/{id}/trail - recent position trail
 */
@RestController
@RequestMapping("/api/tracks")
public class TrackController {

    private final TrackRepository trackRepo;

    public TrackController(TrackRepository trackRepo) {
        this.trackRepo = trackRepo;
    }

    @GetMapping
    public List<Map<String, String>> getAllTracks() {
        return trackRepo.getAllActiveTracks();
    }

    @GetMapping("/{trackId}")
    public ResponseEntity<Map<Object, Object>> getTrack(@PathVariable String trackId) {
        Map<Object, Object> track = trackRepo.getTrack(trackId);
        if (track.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(track);
    }

    @GetMapping("/{trackId}/trail")
    public List<String> getTrail(@PathVariable String trackId) {
        return trackRepo.getTrail(trackId);
    }
}
