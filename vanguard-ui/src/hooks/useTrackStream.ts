import { useEffect, useRef, useState, useCallback } from 'react';
import type { FusedTrack, TrackEvent } from '../lib/types';

const WS_TRACKS_URL = `ws://${window.location.hostname}:8080/ws/tracks`;
const WS_EVENTS_URL = `ws://${window.location.hostname}:8080/ws/events`;
const RECONNECT_DELAY_MS = 2000;
const THROTTLE_INTERVAL_MS = 60; // ~16 FPS visual update rate

interface TrackStreamState {
  tracks: Map<string, FusedTrack>;
  events: TrackEvent[];
  connected: boolean;
  lastUpdateMs: number;
}

/**
 * WebSocket hook for live track and event streaming. Maintains client-side
 * canonical state and throttles visual updates to ~16 FPS while the backend
 * processes at full rate.
 *
 * Reconnects automatically on disconnect.
 */
export function useTrackStream() {
  const [state, setState] = useState<TrackStreamState>({
    tracks: new Map(),
    events: [],
    connected: false,
    lastUpdateMs: 0,
  });

  // Mutable buffer that accumulates updates between throttled flushes
  const bufferRef = useRef<Map<string, FusedTrack>>(new Map());
  const eventBufferRef = useRef<TrackEvent[]>([]);
  const throttleRef = useRef<number | null>(null);

  const flushBuffer = useCallback(() => {
    setState(prev => {
      const merged = new Map(prev.tracks);
      bufferRef.current.forEach((track, id) => {
        if (track.state === 'DROPPED') {
          merged.delete(id);
        } else {
          merged.set(id, track);
        }
      });
      bufferRef.current.clear();

      const newEvents = [...prev.events, ...eventBufferRef.current].slice(-200);
      eventBufferRef.current = [];

      return {
        tracks: merged,
        events: newEvents,
        connected: prev.connected,
        lastUpdateMs: Date.now(),
      };
    });
    throttleRef.current = null;
  }, []);

  const scheduleFlush = useCallback(() => {
    if (throttleRef.current === null) {
      throttleRef.current = window.setTimeout(flushBuffer, THROTTLE_INTERVAL_MS);
    }
  }, [flushBuffer]);

  useEffect(() => {
    let trackWs: WebSocket | null = null;
    let eventWs: WebSocket | null = null;
    let reconnectTimer: number | null = null;

    function connectTracks() {
      trackWs = new WebSocket(WS_TRACKS_URL);

      trackWs.onopen = () => {
        setState(prev => ({ ...prev, connected: true }));
      };

      trackWs.onmessage = (msg) => {
        try {
          const track: FusedTrack = JSON.parse(msg.data);
          bufferRef.current.set(track.trackId, track);
          scheduleFlush();
        } catch (e) {
          console.warn('Invalid track message:', e);
        }
      };

      trackWs.onclose = () => {
        setState(prev => ({ ...prev, connected: false }));
        reconnectTimer = window.setTimeout(connectTracks, RECONNECT_DELAY_MS);
      };

      trackWs.onerror = () => {
        trackWs?.close();
      };
    }

    function connectEvents() {
      eventWs = new WebSocket(WS_EVENTS_URL);

      eventWs.onmessage = (msg) => {
        try {
          const event: TrackEvent = JSON.parse(msg.data);
          eventBufferRef.current.push(event);
          scheduleFlush();
        } catch (e) {
          console.warn('Invalid event message:', e);
        }
      };

      eventWs.onclose = () => {
        window.setTimeout(connectEvents, RECONNECT_DELAY_MS);
      };

      eventWs.onerror = () => {
        eventWs?.close();
      };
    }

    connectTracks();
    connectEvents();

    return () => {
      trackWs?.close();
      eventWs?.close();
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (throttleRef.current) clearTimeout(throttleRef.current);
    };
  }, [scheduleFlush]);

  return state;
}
