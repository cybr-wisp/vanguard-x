import { ShieldCheck } from 'lucide-react'
import type { SystemMetrics } from '../../lib/types'
import { SummaryStat } from '../ui/UiPrimitives'

export function MissionSummary({
  operational,
  active,
  confirmed,
  zones,
  metrics,
}: {
  operational: boolean
  active: number
  confirmed: number
  zones: number
  metrics: SystemMetrics | null
}) {
  return (
    <section className="rail-section">
      <div className="rail-title">MISSION OVERVIEW</div>
      <div className={`mission-summary ${operational ? 'ok' : 'bad'}`}>
        <ShieldCheck size={23} />
        <div>
          <strong>{operational ? 'Pipeline operational' : 'Pipeline degraded'}</strong>
          <span>Live tracks, events, metrics, and zone configuration</span>
        </div>
      </div>

      <div className="summary-grid">
        <SummaryStat label="ACTIVE" value={String(active)} />
        <SummaryStat label="CONFIRMED" value={String(confirmed)} />
        <SummaryStat label="ZONES" value={String(zones)} />
        <SummaryStat label="P99" value={`${(metrics?.p99LatencyMs ?? 0).toFixed(0)} ms`} />
      </div>
    </section>
  )
}
