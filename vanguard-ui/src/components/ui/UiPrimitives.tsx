import React from 'react'
import { Wifi } from 'lucide-react'
import type { FusedTrack, TrackEvent } from '../../lib/types'
import type { ServiceInfo, ServiceState } from '../../lib/uiTypes'
import { fmtTime } from '../../lib/uiUtils'

export function ServiceBadge({ service }: { service: ServiceInfo }) {
  return (
    <div className="service-badge">
      <span className={`service-dot ${service.state.toLowerCase()}`} />
      <div>
        <div className="service-name">{service.name}</div>
        <div className={`service-state ${service.state.toLowerCase()}`}>{service.state}</div>
      </div>
    </div>
  )
}

export function HeaderStat({ label, value, alert = false }: { label: string; value: string; alert?: boolean }) {
  return <div className="header-stat"><span>{label}</span><strong className={alert ? 'alert-text' : ''}>{value}</strong></div>
}

export function MetricCard({ label, value, unit, history }: { label: string; value: string; unit: string; history: number[] }) {
  return (
    <div className="metric-card">
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      <div className="metric-unit">{unit}</div>
      <Sparkline values={history} />
    </div>
  )
}

export function Sparkline({ values }: { values: number[] }) {
  if (values.length < 2) return <div className="sparkline-placeholder" />
  const max = Math.max(...values)
  const min = Math.min(...values)
  const span = Math.max(1e-9, max - min)

  const points = values.map((value, index) => {
    const x = (index / (values.length - 1)) * 100
    const y = 88 - ((value - min) / span) * 68
    return `${x},${y}`
  }).join(' ')

  return (
    <svg className="sparkline" viewBox="0 0 100 100" preserveAspectRatio="none">
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth="2" vectorEffect="non-scaling-stroke" />
    </svg>
  )
}

export function MiniEvent({ event }: { event: TrackEvent }) {
  const colorClass = event.type === 'ZONE_ENTRY' ? 'entry' : event.type === 'ZONE_APPROACH' ? 'approach' : 'exit'
  return (
    <div className="mini-event">
      <span className={`mini-event-dot ${colorClass}`} />
      <div className="mini-event-time mono">{fmtTime(event.timestampMs)}</div>
      <div className="mini-event-copy">
        <strong className={colorClass}>{event.type.replace(/_/g, ' ')}</strong>
        <span>{event.trackId} · {event.zoneId}</span>
      </div>
    </div>
  )
}

export function EventPill({ type }: { type: TrackEvent['type'] }) {
  return <span className={`event-pill ${type.toLowerCase()}`}>{type.replace(/_/g, ' ')}</span>
}

export function StatePill({ state }: { state: FusedTrack['state'] }) {
  return <span className={`state-pill ${state.toLowerCase()}`}>{state}</span>
}

export function StatusPill({ state }: { state: ServiceState }) {
  return <span className={`status-pill ${state.toLowerCase()}`}>{state}</span>
}

export function MapToggle({
  icon,
  label,
  active,
  onClick,
}: {
  icon: React.ReactNode
  label: string
  active: boolean
  onClick: () => void
}) {
  return <button className={`map-toggle ${active ? 'active' : ''}`} onClick={onClick} title={label}>{icon}</button>
}

export function LegendDot({ color, label }: { color: string; label: string }) {
  return <div className="legend-row"><span className="legend-line" style={{ background: color }} />{label}</div>
}

export function TabShell({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
  return (
    <div className="tab-shell">
      <div className="tab-heading">
        <div><h2>{title}</h2><p>{subtitle}</p></div>
      </div>
      {children}
    </div>
  )
}

export function BigMetric({
  label,
  value,
  unit,
  good = false,
}: {
  label: string
  value: string
  unit: string
  good?: boolean
}) {
  return (
    <div className="big-metric">
      <span>{label}</span>
      <strong className={good ? 'good-value' : ''}>{value}</strong>
      <small>{unit}</small>
    </div>
  )
}

export function BenchmarkPanel({ title, children }: { title: string; children: React.ReactNode }) {
  return <div className="benchmark-panel"><div className="benchmark-panel-title">{title}</div>{children}</div>
}

export function TrendPanel({ label, values, unit }: { label: string; values: number[]; unit: string }) {
  return (
    <div className="trend-panel">
      <div className="trend-head">
        <span>{label}</span>
        <strong>{values.length ? values[values.length - 1].toFixed(1) : '0.0'} {unit}</strong>
      </div>
      <Sparkline values={values} />
    </div>
  )
}

export function SummaryStat({ label, value }: { label: string; value: string }) {
  return <div className="summary-stat"><strong>{value}</strong><span>{label}</span></div>
}

export function InfoRow({ label, value }: { label: string; value: string }) {
  return <div className="info-row"><span>{label}</span><strong>{value}</strong></div>
}

export function TransportRow({ label, connected }: { label: string; connected: boolean }) {
  return (
    <div className="transport-row">
      <Wifi size={15} />
      <span>{label}</span>
      <strong className={connected ? 'ok-text' : 'alert-text'}>{connected ? 'CONNECTED' : 'OFFLINE'}</strong>
    </div>
  )
}

export function EmptyState({ text }: { text: string }) {
  return <div className="empty-state">{text}</div>
}
