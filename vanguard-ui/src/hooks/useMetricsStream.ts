import { useEffect, useState } from 'react';
import type { SystemMetrics } from '../lib/types';

const WS_HEALTH_URL = `ws://${window.location.hostname}:8080/ws/health`;
const RECONNECT_DELAY_MS = 3000;

/**
 * WebSocket hook for system health metrics. Updates are typically pushed
 * every 1-2 seconds from the backend, so no client-side throttling is needed.
 */
export function useMetricsStream() {
  const [metrics, setMetrics] = useState<SystemMetrics | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    let ws: WebSocket | null = null;
    let reconnectTimer: number | null = null;

    function connect() {
      ws = new WebSocket(WS_HEALTH_URL);

      ws.onopen = () => setConnected(true);

      ws.onmessage = (msg) => {
        try {
          setMetrics(JSON.parse(msg.data));
        } catch (e) {
          console.warn('Invalid metrics message:', e);
        }
      };

      ws.onclose = () => {
        setConnected(false);
        reconnectTimer = window.setTimeout(connect, RECONNECT_DELAY_MS);
      };

      ws.onerror = () => ws?.close();
    }

    connect();

    return () => {
      ws?.close();
      if (reconnectTimer) clearTimeout(reconnectTimer);
    };
  }, []);

  return { metrics, connected };
}
