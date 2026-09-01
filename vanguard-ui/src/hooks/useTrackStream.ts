import { useEffect, useState } from 'react';
import type { FusedTrack, TrackEvent } from '../lib/types';

const WS_HOST = `ws://${window.location.hostname}:8081`;

// Module-level singleton state -- survives React re-renders and HMR
let globalTracks = new Map<string, FusedTrack>();
let globalEvents: TrackEvent[] = [];
let globalConnected = false;
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
    ws.onopen = () => { globalConnected = true; notify(); };
    ws.onmessage = (msg) => {
      try {
        const track: FusedTrack = JSON.parse(msg.data);
        if (track.state === 'DROPPED') {
          globalTracks.delete(track.trackId);
        } else {
          globalTracks.set(track.trackId, track);
        }
        globalTracks = new Map(globalTracks);
        notify();
      } catch (e) {}
    };
    ws.onclose = () => {
      globalConnected = false;
      notify();
      setTimeout(connectTracks, 2000);
    };
    ws.onerror = () => ws.close();
  }

  function connectEvents() {
    const ws = new WebSocket(`${WS_HOST}/ws/events`);
    ws.onmessage = (msg) => {
      try {
        const event: TrackEvent = JSON.parse(msg.data);
        globalEvents = [...globalEvents.slice(-200), event];
        notify();
      } catch (e) {}
    };
    ws.onclose = () => setTimeout(connectEvents, 2000);
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
      listeners = listeners.filter(l => l !== listener);
    };
  }, []);

  return {
    tracks: globalTracks,
    events: globalEvents,
    connected: globalConnected,
    lastUpdateMs: Date.now(),
  };
}
