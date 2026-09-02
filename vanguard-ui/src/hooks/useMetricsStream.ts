import { useEffect, useState } from 'react';
import type { SystemMetrics } from '../lib/types';

const BACKEND_HOST =
  (import.meta.env.VITE_BACKEND_HOST as string | undefined)?.trim()
  || `${window.location.hostname}:8081`;

const WS_SCHEME = window.location.protocol === 'https:' ? 'wss' : 'ws';
const WS_HOST = `${WS_SCHEME}://${BACKEND_HOST}`;

let globalMetrics: SystemMetrics | null = null;
let globalConnected = false;
let globalLastMessageMs = 0;
let listeners: Array<() => void> = [];
let started = false;

function notify() {
  listeners.forEach(fn => fn());
}

function startHealthSocket() {
  if (started) return;
  started = true;

  function connect() {
    const ws = new WebSocket(`${WS_HOST}/ws/health`);

    ws.onopen = () => {
      globalConnected = true;
      notify();
    };

    ws.onmessage = (msg) => {
      try {
        globalMetrics = JSON.parse(msg.data);
        globalLastMessageMs = Date.now();
        notify();
      } catch {
        // Ignore malformed server messages.
      }
    };

    ws.onclose = () => {
      globalConnected = false;
      notify();
      window.setTimeout(connect, 3_000);
    };

    ws.onerror = () => ws.close();
  }

  connect();
}

export function useMetricsStream() {
  const [, forceUpdate] = useState(0);

  useEffect(() => {
    startHealthSocket();

    const listener = () => forceUpdate(n => n + 1);
    listeners.push(listener);

    return () => {
      listeners = listeners.filter(existing => existing !== listener);
    };
  }, []);

  return {
    metrics: globalMetrics,
    connected: globalConnected,
    lastMessageMs: globalLastMessageMs,
  };
}