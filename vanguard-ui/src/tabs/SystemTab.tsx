import { Activity, Database, Server } from 'lucide-react'
import type { SystemMetrics } from '../lib/types'
import type { ServiceInfo } from '../lib/uiTypes'
import {
  InfoRow,
  StatusPill,
  TabShell,
  TransportRow,
} from '../components/ui/UiPrimitives'

export function SystemTab({
  services,
  metrics,
  uptime,
  trackConnected,
  eventConnected,
  metricsConnected,
  zonesConnected,
  zoneCount,
}: {
  services: ServiceInfo[]
  metrics: SystemMetrics | null
  uptime: string
  trackConnected: boolean
  eventConnected: boolean
  metricsConnected: boolean
  zonesConnected: boolean
  zoneCount: number
}) {
  return (
    <TabShell title="System Health" subtitle="Statuses are derived from real WebSocket connectivity, zone configuration, Kafka lag, and persistence metrics.">
      <div className="system-grid">
        {services.map(service => (
          <div className="system-service-card" key={service.name}>
            <div className="system-service-icon">
              {service.name === 'REDIS' ? <Database size={19} /> : service.name === 'KAFKA' ? <Server size={19} /> : <Activity size={19} />}
            </div>
            <div className="system-service-main">
              <strong>{service.name}</strong>
              <span>{service.detail}</span>
            </div>
            <StatusPill state={service.state} />
          </div>
        ))}
      </div>

      <div className="section-title">STREAM TRANSPORT</div>
      <div className="transport-grid">
        <TransportRow label="/ws/tracks" connected={trackConnected} />
        <TransportRow label="/ws/events" connected={eventConnected} />
        <TransportRow label="/ws/health" connected={metricsConnected} />
        <TransportRow label="/api/zones" connected={zonesConnected} />
      </div>

      <div className="section-title">BACKEND HEALTH PAYLOAD</div>
      <div className="health-payload">
        <InfoRow label="Uptime" value={uptime} />
        <InfoRow label="Configured geofences" value={String(zoneCount)} />
        <InfoRow label="Gateway packets received" value={(metrics?.gatewayPacketsReceived ?? 0).toLocaleString()} />
        <InfoRow label="Gateway packets accepted" value={(metrics?.gatewayPacketsAccepted ?? 0).toLocaleString()} />
        <InfoRow label="Track Kafka lag" value={String(metrics?.trackKafkaLag ?? 0)} />
        <InfoRow label="Event Kafka lag" value={String(metrics?.eventKafkaLag ?? 0)} />
        <InfoRow label="Pending Redis writes" value={String(metrics?.pendingTrackPersistence ?? 0)} />
        <InfoRow label="Persistence writes skipped" value={String(metrics?.trackPersistenceSkipped ?? 0)} />
      </div>
    </TabShell>
  )
}
