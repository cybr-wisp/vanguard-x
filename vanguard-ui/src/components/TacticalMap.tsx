import React, { useRef, useEffect, useState } from 'react';
import maplibregl from 'maplibre-gl';
import { useTrackStream } from '../hooks/useTrackStream';
import { TrackLayer } from './TrackLayer';
import { SensorLayer } from './SensorLayer';
import { CovarianceEllipse } from './CovarianceEllipse';
import { GeofenceLayer } from './GeofenceLayer';
import { TrackInspector } from './TrackInspector';
import { MetricsPanel } from './MetricsPanel';
import type { FusedTrack, SensorPosition, RestrictedZone } from '../lib/types';

interface TacticalMapProps {
  sensors: SensorPosition[];
  zones: RestrictedZone[];
}

/**
 * Main tactical map component. Renders the operational picture with:
 *   - Raw sensor observations (scatter)
 *   - Fused tracks (dots + trails + heading markers)
 *   - Covariance ellipses (uncertainty visualization)
 *   - Geofence zones (polygons with buffer rings)
 *   - Track inspector (click a track to see details)
 *   - Metrics panel (throughput, latency, queue depth)
 *
 * The map renders at a throttled visual rate (~16 FPS) independent from
 * the backend ingest rate. Dropped browser frames do not affect backend
 * correctness.
 */
export const TacticalMap: React.FC<TacticalMapProps> = ({ sensors, zones }) => {
  const mapContainer = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const { tracks, events, connected } = useTrackStream();
  const [selectedTrack, setSelectedTrack] = useState<FusedTrack | null>(null);
  const [showRaw, setShowRaw] = useState(true);
  const [showFused, setShowFused] = useState(true);
  const [showEllipses, setShowEllipses] = useState(true);

  useEffect(() => {
    if (!mapContainer.current) return;

    const map = new maplibregl.Map({
      container: mapContainer.current,
      style: {
        version: 8,
        sources: {},
        layers: [{
          id: 'background',
          type: 'background',
          paint: { 'background-color': '#0a1628' }
        }]
      },
      center: [0, 0],
      zoom: 10,
      attributionControl: false,
    });

    mapRef.current = map;
    return () => map.remove();
  }, []);

  const trackArray = Array.from(tracks.values());

  return (
    <div style={{ position: 'relative', width: '100%', height: '100vh' }}>
      <div ref={mapContainer} style={{ width: '100%', height: '100%' }} />

      {/* Connection status indicator */}
      <div style={{
        position: 'absolute', top: 12, left: 12,
        padding: '4px 12px', borderRadius: 4,
        background: connected ? '#1a7a3a' : '#7a1a1a',
        color: '#fff', fontSize: 12, fontFamily: 'monospace'
      }}>
        {connected ? 'LIVE' : 'DISCONNECTED'}
      </div>

      {/* Visibility toggles */}
      <div style={{
        position: 'absolute', top: 12, right: 12,
        display: 'flex', gap: 8, fontFamily: 'monospace', fontSize: 12
      }}>
        <label style={{ color: '#aaa' }}>
          <input type="checkbox" checked={showRaw} onChange={e => setShowRaw(e.target.checked)} />
          {' '}Raw
        </label>
        <label style={{ color: '#4af' }}>
          <input type="checkbox" checked={showFused} onChange={e => setShowFused(e.target.checked)} />
          {' '}Fused
        </label>
        <label style={{ color: '#fa4' }}>
          <input type="checkbox" checked={showEllipses} onChange={e => setShowEllipses(e.target.checked)} />
          {' '}Ellipses
        </label>
      </div>

      {/* Render layers as SVG overlay (simpler than MapLibre sources for local coords) */}
      <svg style={{
        position: 'absolute', top: 0, left: 0,
        width: '100%', height: '100%', pointerEvents: 'none'
      }}>
        <GeofenceLayer zones={zones} />
        {showRaw && <SensorLayer sensors={sensors} />}
        {showFused && <TrackLayer
          tracks={trackArray}
          onSelect={setSelectedTrack}
        />}
        {showEllipses && trackArray.map(t => (
          <CovarianceEllipse key={t.trackId} track={t} />
        ))}
      </svg>

      {/* Track inspector panel */}
      {selectedTrack && (
        <TrackInspector
          track={selectedTrack}
          events={events.filter(e => e.trackId === selectedTrack.trackId)}
          onClose={() => setSelectedTrack(null)}
        />
      )}

      {/* Metrics panel */}
      <MetricsPanel />
    </div>
  );
};
