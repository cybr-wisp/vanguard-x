import { ShieldCheck } from 'lucide-react'
import type { SystemMetrics } from '../lib/types'
import { BENCHMARK } from '../config/benchmark'
import { BigMetric, TabShell, TrendPanel } from '../components/ui/UiPrimitives'
import { fmtCompact } from '../lib/uiUtils'

export function AnalyticsTab({
  metrics,
  dropRate,
  histories,
}: {
  metrics: SystemMetrics | null
  dropRate: number
  histories: { ingest: number[]; active: number[]; latency: number[]; lag: number[]; loss: number[] }
}) {
  return (
    <TabShell title="Live Analytics" subtitle="Streaming values from /ws/health. Benchmark figures are clearly separated from live telemetry.">
      <div className="analytics-grid">
        <BigMetric label="Throughput" value={fmtCompact(metrics?.throughputReportsPerSec ?? 0)} unit="reports/s" />
        <BigMetric label="P50 latency" value={(metrics?.p50LatencyMs ?? 0).toFixed(1)} unit="ms" />
        <BigMetric label="P95 latency" value={(metrics?.p95LatencyMs ?? 0).toFixed(1)} unit="ms" />
        <BigMetric label="P99 latency" value={(metrics?.p99LatencyMs ?? 0).toFixed(1)} unit="ms" />
        <BigMetric label="Kafka lag" value={String(metrics?.kafkaLag ?? 0)} unit="messages" />
        <BigMetric label="Gateway drop rate" value={dropRate.toFixed(2)} unit="%" />
      </div>

      <div className="section-title">LIVE TREND WINDOW</div>
      <div className="trend-grid">
        <TrendPanel label="INGEST RATE" values={histories.ingest} unit="reports/s" />
        <TrendPanel label="P99 LATENCY" values={histories.latency} unit="ms" />
        <TrendPanel label="KAFKA LAG" values={histories.lag} unit="messages" />
      </div>

      <div className="benchmark-reference">
        <ShieldCheck size={18} />
        <div>
          <strong>Measured benchmark reference</strong>
          <span>{BENCHMARK.throughput[200].toLocaleString()} reports/s @ 200 targets · {BENCHMARK.positionRmse.toFixed(2)} m RMSE · {BENCHMARK.association}% association</span>
        </div>
      </div>
    </TabShell>
  )
}
