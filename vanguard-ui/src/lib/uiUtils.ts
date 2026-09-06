import type { FusedTrack } from './types'

export function appendHistory(values: number[], next: number) {
  return [...values.slice(-39), Number.isFinite(next) ? next : 0]
}

export function validLngLat(lng: number, lat: number) {
  return Number.isFinite(lng) && Number.isFinite(lat) && Math.abs(lng) <= 180 && Math.abs(lat) <= 90
}

export function stateRank(state: FusedTrack['state']) {
  return state === 'CONFIRMED' ? 0 : state === 'COASTING' ? 1 : state === 'TENTATIVE' ? 2 : 3
}

export function headingDeg(vx: number, vy: number) {
  return (Math.atan2(vx, vy) * 180 / Math.PI + 360) % 360
}

export function fmtTime(ms: number) {
  if (!Number.isFinite(ms) || ms <= 0) return 'â€”'
  return new Date(ms).toISOString().slice(11, 19) + 'Z'
}

export function fmtAge(ms: number) {
  if (!Number.isFinite(ms)) return 'â€”'
  if (ms < 1_000) return `${Math.max(0, ms)} ms ago`
  if (ms < 60_000) return `${(ms / 1_000).toFixed(1)} s ago`
  return `${Math.floor(ms / 60_000)}m ago`
}

export function fmtDuration(ms: number) {
  const totalSeconds = Math.max(0, Math.floor(ms / 1_000))
  const days = Math.floor(totalSeconds / 86_400)
  const hours = Math.floor((totalSeconds % 86_400) / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60

  if (days) return `${days}d ${hours}h ${minutes}m`
  if (hours) return `${hours}h ${minutes}m ${seconds}s`
  if (minutes) return `${minutes}m ${seconds}s`
  return `${seconds}s`
}

export function fmtCompact(value: number) {
  if (!Number.isFinite(value)) return '0'
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (Math.abs(value) >= 1_000) return `${(value / 1_000).toFixed(1)}K`
  return Math.round(value).toLocaleString()
}
