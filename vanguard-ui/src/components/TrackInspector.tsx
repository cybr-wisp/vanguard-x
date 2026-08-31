import React from 'react';
import type { FusedTrack, TrackEvent } from '../lib/types';

interface TrackInspectorProps {
  track: FusedTrack;
  events: TrackEvent[];
  onClose: () => void;
}

/**
 * Side panel showing details for a selected track:
 *   - Position, velocity, speed, heading
 *   - Lifecycle state and confidence proxy (1/uncertainty)
 *   - Contributing sensors
 *   - Covariance ellipse dimensions
 *   - Recent events for this track
 */
export const TrackInspector: React.FC<TrackInspectorProps> = ({ track, events, onClose }) => {
  const speed = Math.sqrt(track.vx * track.vx + track.vy * track.vy);
  const heading = Math.atan2(track.vy, track.vx) * (180 / Math.PI);

  return (
    <div style={{
      position: 'absolute', top: 60, right: 12, width: 280,
      background: 'rgba(10, 22, 40, 0.95)', border: '1px solid #335',
      borderRadius: 6, padding: 16, color: '#ccc',
      fontFamily: 'monospace', fontSize: 12,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
        <span style={{ color: '#4af', fontWeight: 'bold' }}>{track.trackId}</span>
        <button onClick={onClose} style={{
          background: 'none', border: 'none', color: '#888', cursor: 'pointer', fontSize: 14
        }}>x</button>
      </div>

      <div style={{ marginBottom: 8 }}>
        <Row label="State" value={track.state} />
        <Row label="Position" value={`(${track.px.toFixed(1)}, ${track.py.toFixed(1)})`} />
        <Row label="Velocity" value={`(${track.vx.toFixed(1)}, ${track.vy.toFixed(1)})`} />
        <Row label="Speed" value={`${speed.toFixed(1)} m/s`} />
        <Row label="Heading" value={`${heading.toFixed(1)} deg`} />
        <Row label="Uncertainty" value={`${track.uncertainty.toFixed(1)} m`} />
        <Row label="Sensors" value={track.contributingSensors.join(', ')} />
      </div>

      {track.ellipseMajor && (
        <div style={{ marginBottom: 8 }}>
          <Row label="Ellipse major" value={`${track.ellipseMajor.toFixed(1)} m`} />
          <Row label="Ellipse minor" value={`${track.ellipseMinor?.toFixed(1)} m`} />
        </div>
      )}

      {events.length > 0 && (
        <div>
          <div style={{ color: '#888', marginBottom: 4 }}>Recent events:</div>
          {events.slice(-5).map((e, i) => (
            <div key={i} style={{ color: '#fa4', fontSize: 10, marginBottom: 2 }}>
              {e.type} {e.zoneId} @ {new Date(e.timestampMs).toLocaleTimeString()}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

const Row: React.FC<{ label: string; value: string | undefined }> = ({ label, value }) => (
  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 2 }}>
    <span style={{ color: '#666' }}>{label}</span>
    <span>{value || '-'}</span>
  </div>
);
