import React, { useEffect, useMemo, useRef, useState } from 'react'
import ReactDOM from 'react-dom/client'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import './styles.css'
import './glowup.css'
import {
  Activity,
  AlertTriangle,
  BarChart3,
  Crosshair,
  Database,
  Filter,
  Layers,
  List,
  Map as MapIcon,
  Maximize2,
  Plane,
  Radio,
  Server,
  Settings,
  ShieldCheck,
  Target,
  X,
} from 'lucide-react'
import { useTrackStream } from './hooks/useTrackStream'
import { useMetricsStream } from './hooks/useMetricsStream'
import { useZoneConfig } from './hooks/useZoneConfig'
import type { FusedTrack, SystemMetrics, TrackEvent, ZoneDefinition } from './lib/types'
import { BENCHMARK } from './config/benchmark'
import {
  MAP_CENTER,
  MAP_ZOOM,
  METERS_PER_DEG_LAT,
  METERS_PER_DEG_LNG,
  SATELLITE_BASEMAP,
  SENSORS,
  TRACK_COLORS,
  TRACK_STALE_MS,
} from './config/tactical'
import type {
  ServiceInfo,
  ServiceState,
  Tab,
  TrackNotice,
  TrailPoint,
} from './lib/uiTypes'
import {
  appendHistory,
  fmtAge,
  fmtCompact,
  fmtDuration,
  fmtTime,
  headingDeg,
  stateRank,
  validLngLat,
} from './lib/uiUtils'
import {
  BenchmarkPanel,
  BigMetric,
  EmptyState,
  EventPill,
  HeaderStat,
  InfoRow,
  LegendDot,
  MapToggle,
  MetricCard,
  MiniEvent,
  ServiceBadge,
  Sparkline,
  StatePill,
  StatusPill,
  SummaryStat,
  TabShell,
  TransportRow,
  TrendPanel,
} from './components/ui/UiPrimitives'
import { TracksTab } from './tabs/TracksTab'
import { EventsTab } from './tabs/EventsTab'
import { SensorsTab } from './tabs/SensorsTab'
import { AnalyticsTab } from './tabs/AnalyticsTab'
import { SystemTab } from './tabs/SystemTab'
import { BenchmarksTab } from './tabs/BenchmarksTab'
import { TrackInspector } from './components/dashboard/TrackInspector'
import { MissionSummary } from './components/dashboard/MissionSummary'
import { RecentEvents } from './components/dashboard/RecentEvents'
import { SensorStatus } from './components/dashboard/SensorStatus'
import { EventFeed } from './components/dashboard/EventFeed'
import { MapView } from './components/MapView'

const NAV: Array<[Tab, string, React.FC<any>]> = [
  ['OVERVIEW', 'Overview', MapIcon],
  ['TRACKS', 'Tracks', Crosshair],
  ['EVENTS', 'Events', List],
  ['SENSORS', 'Sensors', Radio],
  ['ANALYTICS', 'Analytics', BarChart3],
  ['SYSTEM', 'System Health', Settings],
  ['BENCHMARKS', 'Benchmarks', Target],
]

function App() {
  const mapContainer = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const trails = useRef<Map<string, TrailPoint[]>>(new Map())
  const [tab, setTab] = useState<Tab>('OVERVIEW')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [layers, setLayers] = useState({ tracks: true, ellipse: true, geo: true, trails: true, sensors: true })
  const [, forceTrailRender] = useState(0)
  const [now, setNow] = useState(Date.now())
  const previousTrackStates = useRef<Map<string, FusedTrack['state']>>(new Map())
  const [trackNotices, setTrackNotices] = useState<TrackNotice[]>([])

  const metricsHistory = useRef<{ ingest: number[]; active: number[]; latency: number[]; lag: number[]; loss: number[] }>({
    ingest: [], active: [], latency: [], lag: [], loss: [],
  })

  const {
    tracks,
    events,
    trackConnected,
    eventConnected,
    lastTrackMessageMs,
    lastEventMessageMs,
  } = useTrackStream()

  const { metrics, connected: metricsConnected, lastMessageMs: lastMetricsMessageMs } = useMetricsStream()
  const { zones, connected: zonesConnected } = useZoneConfig()

  useEffect(() => {
    const nextStates = new Map<string, FusedTrack['state']>()
    const notices: TrackNotice[] = []

    tracks.forEach((track, id) => {
      const previous = previousTrackStates.current.get(id)

      if (previous === 'CONFIRMED' && track.state === 'COASTING') {
        notices.push({
          id: `${id}-lost-${track.lastUpdateMs}`,
          trackId: id,
          kind: 'SIGNAL LOST',
          ts: Date.now(),
        })
      } else if (previous === 'COASTING' && track.state === 'CONFIRMED') {
        notices.push({
          id: `${id}-reacquired-${track.lastUpdateMs}`,
          trackId: id,
          kind: 'REACQUIRED',
          ts: Date.now(),
        })
      }

      nextStates.set(id, track.state)
    })

    previousTrackStates.current = nextStates

    if (notices.length > 0) {
      setTrackNotices(current => [...current, ...notices].slice(-4))
    }
  }, [tracks])

  useEffect(() => {
    setTrackNotices(current =>
      current.filter(notice => now - notice.ts < 5_000)
    )
  }, [now])

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    tracks.forEach((track, id) => {
      if (track.state === 'DROPPED') {
        trails.current.delete(id)
        return
      }
      if (!validLngLat(track.px, track.py)) return

      const trail = trails.current.get(id) || []
      const last = trail[trail.length - 1]
      if (!last || last.ts !== track.lastUpdateMs || last.lng !== track.px || last.lat !== track.py) {
        trail.push({ lng: track.px, lat: track.py, ts: track.lastUpdateMs })
      }

      const cutoff = track.lastUpdateMs - 35_000
      while (trail.length && trail[0].ts < cutoff) trail.shift()
      if (trail.length > 1_500) trail.splice(0, trail.length - 1_500)
      trails.current.set(id, trail)
    })
    forceTrailRender(value => value + 1)
  }, [tracks])

  const aliveTracks = useMemo(() => {
    const result: Array<[string, FusedTrack]> = []
    tracks.forEach((track, id) => {
      if (
        track.state !== 'DROPPED' &&
        validLngLat(track.px, track.py) &&
        Number.isFinite(track.lastUpdateMs) &&
        now - track.lastUpdateMs <= TRACK_STALE_MS
      ) {
        result.push([id, track])
      }
    })

    return result.sort((a, b) =>
      stateRank(a[1].state) - stateRank(b[1].state) ||
      a[0].localeCompare(b[0])
    )
  }, [tracks, now])

  useEffect(() => {
    if (selectedId && !aliveTracks.some(([id]) => id === selectedId)) {
      setSelectedId(null)
    }
  }, [aliveTracks, selectedId])

  const selectedTrack = selectedId ? tracks.get(selectedId) ?? null : null
  const visibleActive = aliveTracks.length
  const visibleConfirmed = aliveTracks.filter(([, track]) => track.state === 'CONFIRMED').length

  const gatewayReceived = metrics?.gatewayPacketsReceived ?? 0
  const gatewayAccepted = metrics?.gatewayPacketsAccepted ?? 0
  const gatewayDropped = metrics?.packetsDropped ?? 0
  const gatewayDropRate = gatewayReceived > 0 ? (gatewayDropped / gatewayReceived) * 100 : 0

  useEffect(() => {
    if (!metrics) return
    const history = metricsHistory.current
    history.ingest = appendHistory(history.ingest, metrics.throughputReportsPerSec)
    history.active = appendHistory(history.active, visibleActive)
    history.latency = appendHistory(history.latency, metrics.p99LatencyMs)
    history.lag = appendHistory(history.lag, metrics.kafkaLag)
    history.loss = appendHistory(history.loss, gatewayDropRate)
  }, [metrics, gatewayDropRate, visibleActive])

  const healthFresh = metricsConnected && now - lastMetricsMessageMs < 5_000
  const trackFresh = trackConnected && (lastTrackMessageMs === 0 || now - lastTrackMessageMs < 5_000)
  const eventFresh = eventConnected && (lastEventMessageMs === 0 || now - lastEventMessageMs < 60_000)

  const services = useMemo<ServiceInfo[]>(() => {
    const kafkaLag = metrics?.kafkaLag ?? 0
    const pendingPersistence = metrics?.pendingTrackPersistence ?? 0
    const skippedPersistence = metrics?.trackPersistenceSkipped ?? 0

    return [
      {
        name: 'GATEWAY',
        state: !healthFresh ? 'OFFLINE' : gatewayAccepted > 0 ? 'ONLINE' : 'IDLE',
        detail: healthFresh ? `${fmtCompact(gatewayAccepted)} accepted` : 'health stream down',
      },
      {
        name: 'KAFKA',
        state: !healthFresh ? 'OFFLINE' : kafkaLag > 250 ? 'DEGRADED' : 'ONLINE',
        detail: healthFresh ? `${kafkaLag} lag` : 'health stream down',
      },
      {
        name: 'TRACKER',
        state: trackFresh ? 'ONLINE' : 'OFFLINE',
        detail: trackFresh ? `${visibleConfirmed} confirmed` : 'track stream down',
      },
      {
        name: 'GEOFENCE',
        state: !zonesConnected ? 'OFFLINE' : eventFresh ? 'ONLINE' : eventConnected ? 'IDLE' : 'DEGRADED',
        detail: zonesConnected ? `${zones.length} backend zones` : 'zone config unavailable',
      },
      {
        name: 'REDIS',
        state: !healthFresh ? 'OFFLINE' : skippedPersistence > 0 || pendingPersistence > 256 ? 'DEGRADED' : 'ONLINE',
        detail: healthFresh ? `${pendingPersistence} pending writes` : 'health stream down',
      },
    ]
  }, [
    healthFresh,
    gatewayAccepted,
    metrics,
    trackFresh,
    visibleConfirmed,
    zonesConnected,
    zones.length,
    eventFresh,
    eventConnected,
  ])

  const operational = trackConnected && eventConnected && metricsConnected && zonesConnected
  const alertCount = useMemo(() => {
    const aliveIds = new Set(aliveTracks.map(([id]) => id))
    const breachState = new Map<string, boolean>()

    const ordered = [...events].sort(
      (a, b) => a.timestampMs - b.timestampMs
    )

    for (const event of ordered) {
      const key = `${event.trackId}|${event.zoneId}`

      if (event.type === 'ZONE_ENTRY') {
        breachState.set(key, true)
      } else if (event.type === 'ZONE_EXIT') {
        breachState.set(key, false)
      }
    }

    let activeBreaches = 0

    for (const [key, active] of breachState) {
      if (!active) continue

      const trackId = key.split('|')[0]

      if (aliveIds.has(trackId)) {
        activeBreaches++
      }
    }

    return activeBreaches
  }, [events, aliveTracks])
  const uptime = metrics ? fmtDuration(metrics.uptimeMs) : 'â€”'

  const centerTrack = (track: FusedTrack) => {
    setTab('OVERVIEW')
    window.setTimeout(() => {
      const map = mapRef.current
      if (!map) return
      map.flyTo({
        center: [track.px, track.py],
        zoom: Math.max(map.getZoom(), 12.4),
        duration: 700,
      })
    }, 0)
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark"><Plane size={20} /></div>
          <div>
            <div className="brand-title">VANGUARD-<span>X</span></div>
            <div className="brand-subtitle">Real-Time Telemetry & Tactical Tracking Platform</div>
          </div>
        </div>

        <div className="mission-block">
          <div>
            <div className="mission-label">MISSION STATUS</div>
            <div className={`mission-value ${operational ? 'ok' : 'bad'}`}>
              <span className="status-dot" />
              {operational ? 'OPERATIONAL' : 'DEGRADED'}
            </div>
          </div>
          <div className="mission-time">{new Date(now).toLocaleTimeString([], { hour12: false })}</div>
        </div>

        <div className="service-strip">
          {services.map(service => <ServiceBadge key={service.name} service={service} />)}
        </div>

        <div className="header-stats">
          <HeaderStat label="ACTIVE" value={String(visibleActive)} />
          <HeaderStat label="CONFIRMED" value={String(visibleConfirmed)} />
          <HeaderStat label="ZONES" value={String(zones.length)} />
          <HeaderStat label="ALERTS" value={String(alertCount)} alert={alertCount > 0} />
        </div>

        <div className="operator">
          <div className="operator-label">Operator</div>
          <div className="operator-value">Control Room</div>
        </div>
      </header>

      <div className="app-body">
        <nav className="sidebar">
          <div className="nav-list">
            {NAV.map(([id, label, Icon]) => (
              <button key={id} className={`nav-item ${tab === id ? 'active' : ''}`} onClick={() => setTab(id)}>
                <Icon size={17} />
                <span>{label}</span>
              </button>
            ))}
          </div>

          <div className="sidebar-health">
            <div className="eyebrow">SYSTEM STATUS</div>
            <div className={`sidebar-health-value ${operational ? 'ok' : 'bad'}`}>
              <span className="status-dot" />
              {operational ? 'ALL STREAMS CONNECTED' : 'STREAM DEGRADED'}
            </div>
            <div className="sidebar-health-row"><span>Uptime</span><strong>{uptime}</strong></div>
            <div className="sidebar-health-row"><span>Gateway</span><strong>{fmtCompact(gatewayAccepted)} accepted</strong></div>
            <div className="sidebar-health-row"><span>Kafka lag</span><strong>{metrics?.kafkaLag ?? 0}</strong></div>
          </div>
        </nav>

        <section className="workspace">
          {trackNotices.length > 0 && (
            <div className="track-transition-stack">
              {trackNotices.map(notice => (
                <div
                  key={notice.id}
                  className={`track-transition ${notice.kind === 'REACQUIRED' ? 'reacquired' : 'lost'}`}
                >
                  <span>{notice.kind}</span>
                  <strong className="mono">{notice.trackId}</strong>
                </div>
              ))}
            </div>
          )}
          <main className="primary-panel">
            <MapView
              visible={tab === 'OVERVIEW'}
              mapContainer={mapContainer}
              mapRef={mapRef}
              aliveTracks={aliveTracks}
              trails={trails}
              layers={layers}
              setLayers={setLayers}
              selectedId={selectedId}
              setSelectedId={setSelectedId}
              connected={operational}
              zones={zones}
            />

            {tab === 'TRACKS' && (
              <TracksTab tracks={aliveTracks} selectedId={selectedId} onSelect={setSelectedId} now={now} />
            )}
            {tab === 'EVENTS' && <EventsTab events={events} />}
            {tab === 'SENSORS' && <SensorsTab tracks={aliveTracks} trackConnected={trackConnected} />}
            {tab === 'ANALYTICS' && (
              <AnalyticsTab metrics={metrics} dropRate={gatewayDropRate} histories={metricsHistory.current} />
            )}
            {tab === 'SYSTEM' && (
              <SystemTab
                services={services}
                metrics={metrics}
                uptime={uptime}
                trackConnected={trackConnected}
                eventConnected={eventConnected}
                metricsConnected={metricsConnected}
                zonesConnected={zonesConnected}
                zoneCount={zones.length}
              />
            )}
            {tab === 'BENCHMARKS' && <BenchmarksTab />}
          </main>

          <aside className="right-rail">
            {selectedTrack && selectedId ? (
              <TrackInspector
                id={selectedId}
                track={selectedTrack}
                events={events}
                zones={zones}
                now={now}
                onClose={() => setSelectedId(null)}
                onCenter={() => centerTrack(selectedTrack)}
              />
            ) : (
              <MissionSummary
                operational={operational}
                active={visibleActive}
                confirmed={visibleConfirmed}
                zones={zones.length}
                metrics={metrics}
              />
            )}

            <RecentEvents events={events} />
            <SensorStatus tracks={aliveTracks} connected={trackConnected} />
          </aside>

          <section className="bottom-dashboard">
            <EventFeed events={events} />
            <div className="metrics-stack">
              <div className="metric-grid">
                <MetricCard label="INGEST RATE" value={fmtCompact(metrics?.throughputReportsPerSec ?? 0)} unit="reports/s" history={metricsHistory.current.ingest} />
                <MetricCard label="ACTIVE TRACKS" value={String(visibleActive)} unit="fresh WS tracks" history={metricsHistory.current.active} />
                <MetricCard label="P99 LATENCY" value={(metrics?.p99LatencyMs ?? 0).toFixed(1)} unit="ms live E2E" history={metricsHistory.current.latency} />
                <MetricCard label="KAFKA LAG" value={String(metrics?.kafkaLag ?? 0)} unit="messages" history={metricsHistory.current.lag} />
                <MetricCard label="GATEWAY DROP RATE" value={gatewayDropRate.toFixed(2)} unit="%" history={metricsHistory.current.loss} />
              </div>

              <div className="service-health-row">
                {services.map(service => (
                  <div className="health-card" key={service.name}>
                    <span className={`service-dot ${service.state.toLowerCase()}`} />
                    <div>
                      <div className="health-name">{service.name}</div>
                      <div className={`health-state ${service.state.toLowerCase()}`}>{service.state}</div>
                    </div>
                    <span className="health-detail">{service.detail}</span>
                  </div>
                ))}
              </div>
            </div>
          </section>
        </section>
      </div>
    </div>
  )
}

ReactDOM.createRoot(document.getElementById('root')!).render(<App />)




