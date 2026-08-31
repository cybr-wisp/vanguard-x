import React from 'react';
import type { RestrictedZone } from '../lib/types';

interface GeofenceLayerProps { zones: RestrictedZone[]; }

/** Renders restricted zone polygons with advisory/warning buffer outlines. */
export const GeofenceLayer: React.FC<GeofenceLayerProps> = ({ zones }) => (
  <g>
    {zones.map(zone => {
      const points = zone.polygon.map(([x, y]) => `${x},${y}`).join(' ');
      return (
        <g key={zone.zoneId}>
          {/* Zone boundary (breach) */}
          <polygon
            points={points}
            fill="rgba(255, 50, 50, 0.08)"
            stroke="#f44"
            strokeWidth={2}
          />
          {/* Zone label */}
          {zone.polygon.length > 0 && (
            <text
              x={zone.polygon[0][0]}
              y={zone.polygon[0][1] - 10}
              fill="#f66" fontSize={10} fontFamily="monospace"
            >
              {zone.zoneId}
            </text>
          )}
        </g>
      );
    })}
  </g>
);
