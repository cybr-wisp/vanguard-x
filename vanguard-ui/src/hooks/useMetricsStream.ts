import { useEffect, useState } from 'react';
import type { SystemMetrics } from '../lib/types';

const WS_HOST = `ws://${window.location.hostname}:8081`;

let globalMetrics: SystemMetrics | null = null;
let globalConnected = false;
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
    ws.onopen = () => { globalConnected = true; notify(); };
    ws.onmessage = (msg) => {
      try {
        globalMetrics = JSON.parse(msg.data);
        notify();
      } catch (e) {}
    };
    ws.onclose = () => {
      globalConnected = false;
      notify();
      setTimeout(connect, 3000);
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
      listeners = listeners.filter(l => l !== listener);
    };
  }, []);

  return { metrics: globalMetrics, connected: globalConnected };
}
