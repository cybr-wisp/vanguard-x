import { useEffect, useState } from 'react';
import type { FusedTrack, TrackEvent } from '../lib/types';

const BACKEND_HOST =
  (import.meta.env.VITE_BACKEND_HOST as string | undefined)?.trim()
  || `${window.location.hostname}:8081`;

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws';
const WS_HOST = `${WS_SCHEME}://${BACKEND_HOST}`;

let globalTracks = new Map<string, FusedTrack>();
let globalEvents: TrackEvent[] = [];
let globalTrackConnected = false;
let globalEventConnected = false;
let globalLastTrackMessageMs = 0;
let globalLastEventMessageMs = 0;
let listeners: Array<() => void> = [];
let started = false;

function notify() {
  listeners.forEach(fn => fn());
}

function startWebSockets() {
  if (started) return;
  started = true;

  function connectTracks() {
    const ws = new WebSocket(`${WS_HOST}/ws/tracks`);

    ws.onopen = () => {
      globalTrackConnected = true;
      notify();
    };

    ws.onmessage = (msg) => {
      try {
        const track: FusedTrack = JSON.parse(msg.data);
        globalLastTrackMessageMs = Date.now();

        if (track.state === 'DROPPED') {
          globalTracks.delete(track.trackId);
        } else {
          globalTracks.set(track.trackId, track);
        }

        globalTracks = new Map(globalTracks);
        notify();
      } catch {
        // Ignore malformed server messages; transport state remains intact.
      }
    };

    ws.onclose = () => {
      globalTrackConnected = false;
      notify();
      window.setTimeout(connectTracks, 2_000);
    };

    ws.onerror = () => ws.close();
  }

  function connectEvents() {
    const ws = new WebSocket(`${WS_HOST}/ws/events`);

    ws.onopen = () => {
      globalEventConnected = true;
      notify();
    };

    ws.onmessage = (msg) => {
      try {
        const event: TrackEvent = JSON.parse(msg.data);
        globalLastEventMessageMs = Date.now();

        const withoutDuplicate = globalEvents.filter(existing => existing.eventId !== event.eventId);
        globalEvents = [...withoutDuplicate.slice(-499), event];
        notify();
      } catch {
        // Ignore malformed server messages.
      }
    };

    ws.onclose = () => {
      globalEventConnected = false;
      notify();
      window.setTimeout(connectEvents, 2_000);
    };

    ws.onerror = () => ws.close();
  }

  connectTracks();
  connectEvents();
}

export function useTrackStream() {
  const [, forceUpdate] = useState(0);

  useEffect(() => {
    startWebSockets();

    const listener = () => forceUpdate(n => n + 1);
    listeners.push(listener);

    return () => {
      listeners = listeners.filter(existing => existing !== listener);
    };
  }, []);

  return {
    tracks: globalTracks,
    events: globalEvents,
    connected: globalTrackConnected && globalEventConnected,
    trackConnected: globalTrackConnected,
    eventConnected: globalEventConnected,
    lastTrackMessageMs: globalLastTrackMessageMs,
    lastEventMessageMs: globalLastEventMessageMs,
  };
}