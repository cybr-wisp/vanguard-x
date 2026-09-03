import React from 'react';
import { useMetricsStream } from '../hooks/useMetricsStream';

/**
 * Bottom-left metrics panel showing actual measured throughput, latency,
 * queue depth, and track counts. Never overlays aspirational numbers;
 * these are live values from Prometheus via the WebSocket.
 */
export const MetricsPanel: React.FC = () => {
  const { metrics, connected } = useMetricsStream();

  if (!metrics) {
    return (
      <div style={panelStyle}>
        <span style={{ color: '#666' }}>Metrics: waiting...</span>
      </div>
    );
  }

  return (
    <div style={panelStyle}>
      <MetricRow label="Throughput" value={`${metrics.throughputReportsPerSec.toFixed(0)} rpt/s`} />
      <MetricRow label="p50" value={`${metrics.p50LatencyMs.toFixed(1)} ms`} />
      <MetricRow label="p95" value={`${metrics.p95LatencyMs.toFixed(1)} ms`} />
      <MetricRow label="p99" value={`${metrics.p99LatencyMs.toFixed(1)} ms`} color="#fa4" />
      <MetricRow label="Active" value={String(metrics.activeTracks)} />
      <MetricRow label="Confirmed" value={String(metrics.confirmedTracks)} color="#4af" />
      <MetricRow label="Coasting" value={String(metrics.coastingTracks)} color="#fa4" />
      <MetricRow label="Queue" value={String(metrics.queueDepth)} />
      <MetricRow label="Drops" value={String(metrics.packetsDropped)}
                 color={metrics.packetsDropped > 0 ? '#f44' : '#4a4'} />
      <MetricRow label="Kafka lag" value={String(metrics.kafkaLag)}
                 color={metrics.kafkaLag > 100 ? '#fa4' : '#4a4'} />
    </div>
  );
};

const panelStyle: React.CSSProperties = {
  position: 'absolute', bottom: 12, left: 12,
  background: 'rgba(10, 22, 40, 0.92)', border: '1px solid #224',
  borderRadius: 6, padding: '10px 14px',
  fontFamily: 'IBM Plex Mono, Cascadia Mono, Consolas, monospace', fontSize: 11, color: '#aaa',
  minWidth: 180,
};

const MetricRow: React.FC<{ label: string; value: string; color?: string }> = ({
  label, value, color
}) => (
  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 1 }}>
    <span style={{ color: '#666' }}>{label}</span>
    <span style={{ color: color || '#ccc' }}>{value}</span>
  </div>
);
