import React from 'react';
import type { FusedTrack } from '../lib/types';

interface TrackLayerProps {
  tracks: FusedTrack[];
  onSelect: (track: FusedTrack) => void;
}

const STATE_COLORS: Record<string, string> = {
  TENTATIVE: '#888888',
  CONFIRMED: '#4488ff',
  COASTING:  '#ffaa44',
  DROPPED:   '#ff4444',
};

/**
 * Renders fused tracks as colored dots with heading indicators.
 * Confirmed tracks are blue, coasting are amber, tentative are grey.
 */
export const TrackLayer: React.FC<TrackLayerProps> = ({ tracks, onSelect }) => {
  return (
    <g>
      {tracks.map(track => {
        const color = STATE_COLORS[track.state] || '#fff';
        const speed = Math.sqrt(track.vx * track.vx + track.vy * track.vy);
        const heading = Math.atan2(track.vy, track.vx);

        // Heading indicator line (length proportional to speed)
        const lineLen = Math.min(speed * 2, 40);
        const hx = track.px + lineLen * Math.cos(heading);
        const hy = track.py + lineLen * Math.sin(heading);

        return (
          <g key={track.trackId}
             style={{ pointerEvents: 'all', cursor: 'pointer' }}
             onClick={() => onSelect(track)}>
            {/* Track dot */}
            <circle
              cx={track.px} cy={track.py} r={4}
              fill={color} stroke="#fff" strokeWidth={1}
              opacity={track.state === 'TENTATIVE' ? 0.5 : 1}
            />
            {/* Heading indicator */}
            {speed > 0.5 && (
              <line
                x1={track.px} y1={track.py}
                x2={hx} y2={hy}
                stroke={color} strokeWidth={1.5}
                opacity={0.7}
              />
            )}
            {/* Track ID label */}
            <text
              x={track.px + 8} y={track.py - 6}
              fill={color} fontSize={9} fontFamily="IBM Plex Mono, Cascadia Mono, Consolas, monospace"
              opacity={0.8}
            >
              {track.trackId.replace('TRK-', '')}
            </text>
          </g>
        );
      })}
    </g>
  );
};
