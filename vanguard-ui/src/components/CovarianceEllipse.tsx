import React from 'react';
import type { FusedTrack } from '../lib/types';

interface CovarianceEllipseProps {
  track: FusedTrack;
}

const STATE_COLORS: Record<string, string> = {
  TENTATIVE: '#888888',
  CONFIRMED: '#4488ff',
  COASTING:  '#ffaa44',
};

/**
 * Renders the 95% confidence covariance ellipse for a track.
 * The ellipse visually communicates uncertainty: larger = less certain.
 * During coasting, the ellipse grows as the filter predicts without
 * observations.
 */
export const CovarianceEllipse: React.FC<CovarianceEllipseProps> = ({ track }) => {
  if (track.state === 'DROPPED') return null;
  if (!track.ellipseMajor || !track.ellipseMinor) return null;

  const color = STATE_COLORS[track.state] || '#666';
  const angleDeg = (track.ellipseAngle || 0) * (180 / Math.PI);

  return (
    <ellipse
      cx={track.px}
      cy={track.py}
      rx={track.ellipseMajor}
      ry={track.ellipseMinor}
      transform={`rotate(${angleDeg} ${track.px} ${track.py})`}
      fill="none"
      stroke={color}
      strokeWidth={1}
      strokeDasharray={track.state === 'COASTING' ? '4 3' : 'none'}
      opacity={0.4}
    />
  );
};
