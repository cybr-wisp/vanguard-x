/** Fused track state from the backend WebSocket. */
export interface FusedTrack {
  trackId: string;
  px: number;
  py: number;
  vx: number;
  vy: number;
  state: 'TENTATIVE' | 'CONFIRMED' | 'COASTING' | 'DROPPED';
  uncertainty: number;
  lastUpdateMs: number;
  contributingSensors: string[];
  // Covariance ellipse parameters (computed from P matrix)
  ellipseMajor?: number;
  ellipseMinor?: number;
  ellipseAngle?: number;
}

/** Raw sensor observation (for displaying scatter). */
export interface RawObservation {
  sensorId: string;
  timestampMs: number;
  sensorX: number;
  sensorY: number;
  range: number;
  azimuth: number;
  // Cartesian conversion for rendering
  px: number;
  py: number;
}

/** Track event from zone transitions. */
export interface TrackEvent {
  eventId: string;
  type: 'ZONE_APPROACH' | 'ZONE_ENTRY' | 'ZONE_EXIT';
  trackId: string;
  zoneId: string;
  timestampMs: number;
  previousState: string;
  newState: string;
  px: number;
  py: number;
}

/** Restricted zone definition. */
export interface RestrictedZone {
  zoneId: string;
  polygon: [number, number][];
  warningBufferM: number;
  advisoryBufferM: number;
}

/** System metrics snapshot. */
export interface SystemMetrics {
  throughputReportsPerSec: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  p99LatencyMs: number;
  activeTracks: number;
  confirmedTracks: number;
  coastingTracks: number;
  queueDepth: number;
  kafkaLag: number;
  packetsDropped: number;
  uptimeMs: number;
}

/** Sensor position for the sensor layer. */
export interface SensorPosition {
  sensorId: string;
  x: number;
  y: number;
}
