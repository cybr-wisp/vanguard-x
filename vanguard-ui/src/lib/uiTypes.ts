export type Tab =
  | 'OVERVIEW'
  | 'TRACKS'
  | 'EVENTS'
  | 'SENSORS'
  | 'ANALYTICS'
  | 'SYSTEM'
  | 'BENCHMARKS'

export type ServiceState = 'ONLINE' | 'DEGRADED' | 'IDLE' | 'OFFLINE'

export type ServiceInfo = {
  name: string
  state: ServiceState
  detail: string
}

export type TrailPoint = {
  lng: number
  lat: number
  ts: number
}

export type TrackNotice = {
  id: string
  trackId: string
  kind: 'SIGNAL LOST' | 'REACQUIRED'
  ts: number
}
