/** Fused track state from the backend WebSocket. */
export interface FusedTrack {
  trackId: string
  px: number
  py: number
  vx: number
  vy: number
  state: 'TENTATIVE' | 'CONFIRMED' | 'COASTING' | 'DROPPED'
  uncertainty: number
  lastUpdateMs: number
  contributingSensors: string[]
  ellipseMajor?: number
  ellipseMinor?: number
  ellipseAngle?: number
}

/** Raw sensor observation retained for legacy component compatibility. */
export interface RawObservation {
  sensorId: string
  timestampMs: number
  sensorX: number
  sensorY: number
  range: number
  azimuth: number
  px: number
  py: number
}

/** Track event from the spatial / geofence pipeline. */
export interface TrackEvent {
  eventId: string
  type: 'ZONE_APPROACH' | 'ZONE_ENTRY' | 'ZONE_EXIT'
  trackId: string
  zoneId: string
  timestampMs: number
  previousState: string
  newState: string
  px: number
  py: number
}

export interface RestrictedZone {
  zoneId: string
  polygon: [number, number][]
  warningBufferM: number
  advisoryBufferM: number
}

/** Backend-owned map geometry from GET /api/zones. */
export interface ZoneDefinition {
  zoneId: string
  label: string
  color: string
  center: [number, number]
  warningBufferM: number
  advisoryBufferM: number
  core: [number, number][]
  warning: [number, number][]
  advisory: [number, number][]
}

/** /ws/health payload emitted by KafkaWebSocketBridge. */
export interface SystemMetrics {
  throughputReportsPerSec: number
  p50LatencyMs: number
  p95LatencyMs: number
  p99LatencyMs: number
  activeTracks: number
  confirmedTracks: number
  coastingTracks: number
  queueDepth: number
  kafkaLag: number
  packetsDropped: number
  uptimeMs: number

  gatewayPacketsReceived?: number
  gatewayPacketsAccepted?: number
  trackKafkaLag?: number
  eventKafkaLag?: number
  pendingTrackPersistence?: number
  pendingTrackBroadcasts?: number
  trackPersistenceSkipped?: number
}

export interface SensorPosition {
  sensorId: string
  x: number
  y: number
}
