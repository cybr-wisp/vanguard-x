import React from 'react';
import type { SensorPosition } from '../lib/types';

interface SensorLayerProps { sensors: SensorPosition[]; }

/** Renders fixed sensor positions as diamond markers. */
export const SensorLayer: React.FC<SensorLayerProps> = ({ sensors }) => (
  <g>
    {sensors.map(s => (
      <g key={s.sensorId}>
        <rect
          x={s.x - 5} y={s.y - 5} width={10} height={10}
          transform={`rotate(45 ${s.x} ${s.y})`}
          fill="none" stroke="#4f4" strokeWidth={1.5} opacity={0.7}
        />
        <text x={s.x + 10} y={s.y + 3} fill="#4f4" fontSize={8} fontFamily="IBM Plex Mono, Cascadia Mono, Consolas, monospace">
          {s.sensorId}
        </text>
      </g>
    ))}
  </g>
);
