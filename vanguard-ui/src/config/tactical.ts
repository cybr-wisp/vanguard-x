import type { FusedTrack } from '../lib/types'

export const MAP_CENTER: [number, number] = [-117.13, 34.745]
export const MAP_ZOOM = 10.8

export const METERS_PER_DEG_LNG = 92_000
export const METERS_PER_DEG_LAT = 111_000

export const TRACK_STALE_MS = 10_000

export const SATELLITE_BASEMAP = true

export const SENSORS = [
  { id: 'SSA-01', lng: -117.35, lat: 34.79, type: 'Range / bearing sensor' },
  { id: 'SSB-02', lng: -117.38, lat: 34.71, type: 'Range / bearing sensor' },
  { id: 'SSC-03', lng: -117.05, lat: 34.68, type: 'Range / bearing sensor' },
] as const

export const TRACK_COLORS: Record<FusedTrack['state'], string> = {
  TENTATIVE: '#7d8a99',
  CONFIRMED: '#39b86a',
  COASTING: '#e2a23a',
  DROPPED: '#d9535f',
}
