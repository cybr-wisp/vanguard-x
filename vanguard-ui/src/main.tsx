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
  Wifi,
  X,
} from 'lucide-react'
import { useTrackStream } from './hooks/useTrackStream'
import { useMetricsStream } from './hooks/useMetricsStream'
import { useZoneConfig } from './hooks/useZoneConfig'
import type { FusedTrack, SystemMetrics, TrackEvent, ZoneDefinition } from './lib/types'

const MAP_CENTER: [number, number] = [-117.13, 34.745]
const MAP_ZOOM = 10.8
const METERS_PER_DEG_LNG = 92_000
const METERS_PER_DEG_LAT = 111_000
const TRACK_STALE_MS = 10_000

const SATELLITE_BASEMAP = true

const SENSORS = [
  { id: 'SSA-01', lng: -117.35, lat: 34.79, type: 'Range / bearing sensor' },
  { id: 'SSB-02', lng: -117.38, lat: 34.71, type: 'Range / bearing sensor' },
  { id: 'SSC-03', lng: -117.05, lat: 34.68, type: 'Range / bearing sensor' },
] as const

const TRACK_COLORS: Record<FusedTrack['state'], string> = {
  TENTATIVE: '#7d8a99',
  CONFIRMED: '#39b86a',
  COASTING: '#e2a23a',
  DROPPED: '#d9535f',
}

const BENCHMARK = {
  date: '2026-09-01',
  jvm: '25.0.4.1',
  cores: 8,
  positionRmse: 10.13,
  velocityRmse: 4.33,
  association: 100,
  fragmentation: 2,
  falseTracks: 0,
  rawRmse: 29.24,
  fusedRmse: 10.08,
  fusionGain: 65.5,
  throughput: { 50: 24_413, 200: 20_652, 500: 20_671, 1000: 15_543 },
  fullLatency: { p50: 10.65, p95: 35.14, p99: 70.80, max: 268.88 },
  virtualLatency: { p50: 4.47, p95: 9.56, p99: 17.17 },
  fixedLatency: { p50: 5.09, p95: 15.39, p99: 32.49 },
  covariance: { init: 44.72, update1: 27.60, update6: 20.26, coast10: 349.04, reacquired: 50.04 },
} as const

type Tab = 'OVERVIEW' | 'TRACKS' | 'EVENTS' | 'SENSORS' | 'ANALYTICS' | 'SYSTEM' | 'BENCHMARKS'
type ServiceState = 'ONLINE' | 'DEGRADED' | 'IDLE' | 'OFFLINE'
type ServiceInfo = { name: string; state: ServiceState; detail: string }
type TrailPoint = { lng: number; lat: number; ts: number }

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

function MapView({
  visible,
  mapContainer,
  mapRef,
  aliveTracks,
  trails,
  layers,
  setLayers,
  selectedId,
  setSelectedId,
  connected,
  zones,
}: {
  visible: boolean
  mapContainer: React.RefObject<HTMLDivElement>
  mapRef: React.MutableRefObject<maplibregl.Map | null>
  aliveTracks: Array<[string, FusedTrack]>
  trails: React.MutableRefObject<Map<string, TrailPoint[]>>
  layers: Record<string, boolean>
  setLayers: React.Dispatch<React.SetStateAction<any>>
  selectedId: string | null
  setSelectedId: (id: string) => void
  connected: boolean
  zones: ZoneDefinition[]
}) {
  const [, forceMapRender] = useState(0)

  useEffect(() => {
    if (!mapContainer.current || mapRef.current) return

    const sources: Record<string, any> = {
      imagery: {
        type: 'raster',
        tiles: [
          'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        ],
        tileSize: 256,
        attribution: 'Esri, Maxar, Earthstar Geographics, and the GIS User Community',
      },
      reference: {
        type: 'raster',
        tiles: [
          'https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}',
        ],
        tileSize: 256,
        attribution: 'Esri',
      },
    }

    const styleLayers: any[] = [
      {
        id: 'background',
        type: 'background',
        paint: {
          'background-color': '#070b0c',
        },
      },
      {
        id: 'world-imagery',
        type: 'raster',
        source: 'imagery',
        paint: {
          'raster-opacity': 1,
          'raster-saturation': -0.10,
          'raster-contrast': 0.12,
          'raster-brightness-min': 0.03,
          'raster-brightness-max': 0.90,
        },
      },
      {
        id: 'world-reference',
        type: 'raster',
        source: 'reference',
        paint: {
          'raster-opacity': 0.94,
        },
      },
    ]

    const map = new maplibregl.Map({
      container: mapContainer.current,
      style: { version: 8, sources, layers: styleLayers } as any,
      center: MAP_CENTER,
      zoom: MAP_ZOOM,
      pitch: 0,
      bearing: 0,
      attributionControl: false,
    })

    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'top-right')
    map.addControl(new maplibregl.ScaleControl({ maxWidth: 100, unit: 'metric' }), 'bottom-right')
    map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-left')

    const refresh = () => forceMapRender(value => value + 1)
    map.on('move', refresh)
    map.on('zoom', refresh)
    map.on('resize', refresh)
    map.on('load', refresh)

    mapRef.current = map

    return () => {
      map.off('move', refresh)
      map.off('zoom', refresh)
      map.off('resize', refresh)
      map.remove()
      mapRef.current = null
    }
  }, [mapContainer, mapRef])

  useEffect(() => {
    const map = mapRef.current
    if (!map) return

    if (!map.isStyleLoaded()) {
      const onLoad = () => syncZoneLayers(map, zones, layers.geo)
      map.once('load', onLoad)
      return () => {
        map.off('load', onLoad)
      }
    }

    syncZoneLayers(map, zones, layers.geo)
  }, [zones, layers.geo, mapRef])

  useEffect(() => {
    if (!visible) return
    const timer = window.setTimeout(() => mapRef.current?.resize(), 0)
    return () => window.clearTimeout(timer)
  }, [visible, mapRef])

  return (
    <div className={`map-view ${visible ? 'visible' : 'hidden'}`}>
      <div ref={mapContainer} className="map-canvas" />

      <div className="map-toolbar">
        <MapToggle icon={<Crosshair size={16} />} label="Tracks" active={layers.tracks} onClick={() => setLayers((value: any) => ({ ...value, tracks: !value.tracks }))} />
        <MapToggle icon={<Layers size={16} />} label="Uncertainty" active={layers.ellipse} onClick={() => setLayers((value: any) => ({ ...value, ellipse: !value.ellipse }))} />
        <MapToggle icon={<Filter size={16} />} label="Zones" active={layers.geo} onClick={() => setLayers((value: any) => ({ ...value, geo: !value.geo }))} />
        <MapToggle icon={<Maximize2 size={16} />} label="Trails" active={layers.trails} onClick={() => setLayers((value: any) => ({ ...value, trails: !value.trails }))} />
        <MapToggle icon={<Radio size={16} />} label="Sensors" active={layers.sensors} onClick={() => setLayers((value: any) => ({ ...value, sensors: !value.sensors }))} />
      </div>

      {!connected && (
        <div className="connection-banner">
          <AlertTriangle size={15} />
          Waiting for backend streams on :8081
        </div>
      )}

      <div className="zone-caption">
        <div>{zones.length} ACTIVE GEOFENCES</div>
        <span>BACKEND-SYNCHRONIZED CORE / WARNING / ADVISORY GEOMETRY</span>
      </div>

      <div className="map-legend">
        <LegendDot color={TRACK_COLORS.CONFIRMED} label="Confirmed track" />
        <LegendDot color={TRACK_COLORS.TENTATIVE} label="Tentative track" />
        <LegendDot color={TRACK_COLORS.COASTING} label="Coasting track" />
        <LegendDot color="#d9535f" label="Restricted core" />
        <LegendDot color="#7b4bc4" label="Sensor site" />
      </div>

      <svg className="track-overlay" viewBox={`0 0 ${mapContainer.current?.clientWidth || 1000} ${mapContainer.current?.clientHeight || 700}`}>
        {mapRef.current && layers.geo && zones.map(zone => {
          const point = mapRef.current!.project(zone.center)
          return (
            <g key={`label-${zone.zoneId}`} className="zone-svg-label">
              <rect x={point.x - 47} y={point.y - 11} width={94} height={23} rx={2} fill="rgba(255,255,255,.9)" stroke={zone.color} strokeWidth={1} />
              <text x={point.x} y={point.y - 1} textAnchor="middle" fill={zone.color} fontSize={10} fontWeight={750}>{zone.zoneId}</text>
              <text x={point.x} y={point.y + 9} textAnchor="middle" fill="#627080" fontSize={7.5}>RESTRICTED AIRSPACE</text>
            </g>
          )
        })}

        {mapRef.current && layers.tracks && aliveTracks.map(([id, track], trackIndex) => {
          const map = mapRef.current!
          const point = map.project([track.px, track.py])
          const color = TRACK_COLORS[track.state]
          const selected = selectedId === id
          const heading = headingDeg(track.vx, track.vy)
          const speedMps = Math.hypot(track.vx, track.vy)
          const trail = trails.current.get(id) || []

          // Deterministic tactical label staggering keeps dense
          // multi-target scenes readable without moving the track itself.
          const labelOffsets = [
            { x: 13,   y: -25 },
            { x: 15,   y: 12 },
            { x: -126, y: -27 },
            { x: -126, y: 11 },
            { x: 18,   y: -43 },
            { x: -126, y: -45 },
            { x: 20,   y: 27 },
            { x: -126, y: 27 }
          ]

          const labelOffset =
            labelOffsets[trackIndex % labelOffsets.length]

          const ellipseMajorM = Math.max(1, (track.ellipseMajor ?? track.uncertainty * 2) / 2)
          const ellipseMinorM = Math.max(1, (track.ellipseMinor ?? track.uncertainty * 1.2) / 2)
          const eastPoint = map.project([track.px + ellipseMajorM / METERS_PER_DEG_LNG, track.py])
          const northPoint = map.project([track.px, track.py + ellipseMinorM / METERS_PER_DEG_LAT])
          const rx = Math.min(85, Math.max(4, Math.abs(eastPoint.x - point.x)))
          const ry = Math.min(70, Math.max(3, Math.abs(northPoint.y - point.y)))

          return (
            <g key={id} className="track-group" onClick={() => setSelectedId(id)}>
              {layers.trails && trail.length > 1 && (
                <polyline
                  points={trail.map(trailPoint => {
                    const projected = map.project([trailPoint.lng, trailPoint.lat])
                    return `${projected.x},${projected.y}`
                  }).join(' ')}
                  fill="none"
                  stroke={color}
                  strokeWidth={1.6}
                  opacity={0.72}
                  strokeDasharray="7 6"
                />
              )}

              {layers.ellipse && (
                <ellipse
                  cx={point.x}
                  cy={point.y}
                  rx={rx}
                  ry={ry}
                  transform={`rotate(${track.ellipseAngle ?? heading} ${point.x} ${point.y})`}
                  fill={selected ? 'rgba(22,132,180,.08)' : 'rgba(22,132,180,.025)'}
                  stroke="#238db8"
                  strokeWidth={1.2}
                  opacity={selected ? 0.82 : 0.42}
                  strokeDasharray="4 3"
                />
              )}
              {selected && (
                <circle
                  cx={point.x}
                  cy={point.y}
                  r={18}
                  fill="none"
                  stroke="#fff6d7"
                  strokeWidth={1}
                  opacity={0.72}
                  strokeDasharray="3 4"
                />
              )}

              <g
                transform={`translate(${point.x} ${point.y}) rotate(${heading})`}
                className="aircraft-glyph"
              >
                <path
                  d="M0,-13
                     L2.2,-4.5
                     L10.5,-1
                     L10.5,1.5
                     L2.8,1.2
                     L1.6,8.5
                     L5,11
                     L5,12.5
                     L0,10.8
                     L-5,12.5
                     L-5,11
                     L-1.6,8.5
                     L-2.8,1.2
                     L-10.5,1.5
                     L-10.5,-1
                     L-2.2,-4.5
                     Z"
                  fill={selected ? '#fff8d8' : color}
                  stroke="#06100f"
                  strokeWidth={1.1}
                />
              </g>

              <g transform={`translate(${point.x + labelOffset.x},${point.y + labelOffset.y}) scale(0.84)`}>
                <rect width={140} height={34} rx={2} fill={selected ? 'rgba(6,13,14,.96)' : 'rgba(6,13,14,.88)'} stroke={color} strokeWidth={selected ? 1.4 : 0.8} />
                <text x={9} y={13} fill="#f4f7fa" fontSize={10.5} fontWeight={750}>{id}</text>
                <text x={9} y={26} fill="#a7b4c0" fontSize={8.5}>{track.state} Â· {Math.round(speedMps)} m/s</text>
              </g>
            </g>
          )
        })}

        {mapRef.current && layers.sensors && SENSORS.map(sensor => {
          const point = mapRef.current!.project([sensor.lng, sensor.lat])
          return (
            <g key={sensor.id}>
              <circle cx={point.x} cy={point.y} r={11} fill="rgba(255,255,255,.92)" stroke="#7549bb" strokeWidth={1.6} />
              <circle cx={point.x} cy={point.y} r={3.5} fill="#7549bb" />
              <text x={point.x} y={point.y + 25} fill="#293746" fontSize={10} textAnchor="middle" fontWeight={750}>{sensor.id}</text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

function TracksTab({
  tracks,
  selectedId,
  onSelect,
  now,
}: {
  tracks: Array<[string, FusedTrack]>
  selectedId: string | null
  onSelect: (id: string) => void
  now: number
}) {
  return (
    <TabShell title="Live Tracks" subtitle="Directly rendered from /ws/tracks. No client-side target simulation.">
      <div className="data-table-wrap">
        <table className="data-table">
          <thead><tr><th>Track</th><th>State</th><th>Speed</th><th>Heading</th><th>Uncertainty</th><th>Sensors</th><th>Last update</th></tr></thead>
          <tbody>
            {tracks.map(([id, track]) => (
              <tr key={id} className={selectedId === id ? 'selected-row' : ''} onClick={() => onSelect(id)}>
                <td className="mono strong">{id}</td>
                <td><StatePill state={track.state} /></td>
                <td>{Math.hypot(track.vx, track.vy).toFixed(1)} m/s</td>
                <td>{Math.round(headingDeg(track.vx, track.vy))}Â°</td>
                <td>{track.uncertainty.toFixed(1)} m</td>
                <td>{track.contributingSensors?.join(', ') || 'â€”'}</td>
                <td>{fmtAge(now - track.lastUpdateMs)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {tracks.length === 0 && <EmptyState text="No live tracks. Start the full backend pipeline." />}
      </div>
    </TabShell>
  )
}

function EventsTab({ events }: { events: TrackEvent[] }) {
  const ordered = [...events].sort((a, b) => b.timestampMs - a.timestampMs)
  return (
    <TabShell title="Geofence Events" subtitle="Real transitions emitted by the backend spatial pipeline across all configured zones.">
      <div className="data-table-wrap">
        <table className="data-table">
          <thead><tr><th>Time</th><th>Type</th><th>Track</th><th>Zone</th><th>Transition</th><th>Coordinates</th></tr></thead>
          <tbody>
            {ordered.map(event => (
              <tr key={event.eventId}>
                <td className="mono">{fmtTime(event.timestampMs)}</td>
                <td><EventPill type={event.type} /></td>
                <td className="mono strong">{event.trackId}</td>
                <td>{event.zoneId}</td>
                <td>{event.previousState} â†’ {event.newState}</td>
                <td className="mono">{event.py.toFixed(4)}, {event.px.toFixed(4)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {events.length === 0 && <EmptyState text="No geofence transitions received in this session." />}
      </div>
    </TabShell>
  )
}

function SensorsTab({ tracks, trackConnected }: { tracks: Array<[string, FusedTrack]>; trackConnected: boolean }) {
  return (
    <TabShell title="Sensor Network" subtitle="Sensor positions mirror the active backend scenario. Activity is derived from live track contribution metadata.">
      <div className="sensor-card-grid">
        {SENSORS.map(sensor => {
          const contributors = tracks.filter(([, track]) => track.contributingSensors?.includes(sensor.id)).length
          const active = trackConnected && contributors > 0

          return (
            <div className="sensor-card" key={sensor.id}>
              <div className="sensor-card-head">
                <div className="sensor-icon"><Radio size={18} /></div>
                <div><strong>{sensor.id}</strong><span>{sensor.type}</span></div>
                <StatusPill state={trackConnected ? active ? 'ONLINE' : 'IDLE' : 'OFFLINE'} />
              </div>
              <InfoRow label="Longitude" value={sensor.lng.toFixed(4)} />
              <InfoRow label="Latitude" value={sensor.lat.toFixed(4)} />
              <InfoRow label="Contributing tracks" value={String(contributors)} />
              <InfoRow label="Source" value="Scenario configuration" />
            </div>
          )
        })}
      </div>
    </TabShell>
  )
}

function AnalyticsTab({
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
          <span>{BENCHMARK.throughput[200].toLocaleString()} reports/s @ 200 targets Â· {BENCHMARK.positionRmse.toFixed(2)} m RMSE Â· {BENCHMARK.association}% association</span>
        </div>
      </div>
    </TabShell>
  )
}

function SystemTab({
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

function BenchmarksTab() {
  return (
    <TabShell title="Benchmark Results" subtitle={`Measured ${BENCHMARK.date} Â· JVM ${BENCHMARK.jvm} Â· ${BENCHMARK.cores} cores Â· FullBenchmark`}>
      <div className="benchmark-hero-grid">
        <BigMetric label="Position RMSE" value={BENCHMARK.positionRmse.toFixed(2)} unit="m" good />
        <BigMetric label="Association" value={`${BENCHMARK.association}%`} unit="accuracy" good />
        <BigMetric label="Fusion gain" value={`${BENCHMARK.fusionGain}%`} unit="RMSE reduction" good />
        <BigMetric label="False tracks" value={String(BENCHMARK.falseTracks)} unit="benchmark metric" good />
      </div>

      <div className="benchmark-columns">
        <BenchmarkPanel title="Tracking accuracy">
          <InfoRow label="Position RMSE" value={`${BENCHMARK.positionRmse.toFixed(2)} m`} />
          <InfoRow label="Velocity RMSE" value={`${BENCHMARK.velocityRmse.toFixed(2)} m/s`} />
          <InfoRow label="Association accuracy" value={`${BENCHMARK.association.toFixed(1)}%`} />
          <InfoRow label="Fragmentation" value={String(BENCHMARK.fragmentation)} />
        </BenchmarkPanel>

        <BenchmarkPanel title="Fusion vs raw">
          <InfoRow label="Raw RMSE" value={`${BENCHMARK.rawRmse.toFixed(2)} m`} />
          <InfoRow label="Fused RMSE" value={`${BENCHMARK.fusedRmse.toFixed(2)} m`} />
          <InfoRow label="Improvement" value={`${BENCHMARK.fusionGain}%`} />
          <InfoRow label="Event deduplication" value="1000 â†’ 1" />
          <InfoRow label="Replay determinism" value="IDENTICAL Â· Î” 0.00e+00 m" />
        </BenchmarkPanel>

        <BenchmarkPanel title="Throughput Â· after spatial index">
          {Object.entries(BENCHMARK.throughput).map(([targets, rate]) => (
            <InfoRow key={targets} label={`${targets} targets`} value={`${rate.toLocaleString()} reports/s`} />
          ))}
        </BenchmarkPanel>

        <BenchmarkPanel title="Latency">
          <InfoRow label="Full pipeline p50" value={`${BENCHMARK.fullLatency.p50.toFixed(2)} ms`} />
          <InfoRow label="Full pipeline p95" value={`${BENCHMARK.fullLatency.p95.toFixed(2)} ms`} />
          <InfoRow label="Full pipeline p99" value={`${BENCHMARK.fullLatency.p99.toFixed(2)} ms`} />
          <InfoRow label="Virtual executor p99" value={`${BENCHMARK.virtualLatency.p99.toFixed(2)} ms`} />
          <InfoRow label="Fixed executor p99" value={`${BENCHMARK.fixedLatency.p99.toFixed(2)} ms`} />
        </BenchmarkPanel>

        <BenchmarkPanel title="Covariance honesty">
          <InfoRow label="After init" value={`${BENCHMARK.covariance.init.toFixed(2)} m`} />
          <InfoRow label="After first update" value={`${BENCHMARK.covariance.update1.toFixed(2)} m`} />
          <InfoRow label="After six updates" value={`${BENCHMARK.covariance.update6.toFixed(2)} m`} />
          <InfoRow label="After ten coast cycles" value={`${BENCHMARK.covariance.coast10.toFixed(2)} m`} />
          <InfoRow label="After reacquisition" value={`${BENCHMARK.covariance.reacquired.toFixed(2)} m`} />
        </BenchmarkPanel>

        <BenchmarkPanel title="Loss robustness">
          <InfoRow label="0% loss" value="100% association" />
          <InfoRow label="5% loss" value="100% association" />
          <InfoRow label="10% loss" value="100% association" />
          <InfoRow label="20% loss" value="100% association" />
          <InfoRow label="Re-entry correctness" value="new ZONE_ENTRY emitted" />
        </BenchmarkPanel>
      </div>

      <div className="cli-note">
        <span>REPRODUCE</span>
        <code>mvn -pl benchmarks exec:java "-Dexec.mainClass=com.vanguard.benchmark.FullBenchmark"</code>
      </div>
    </TabShell>
  )
}

function TrackInspector({
  id,
  track,
  events,
  zones,
  now,
  onClose,
  onCenter,
}: {
  id: string
  track: FusedTrack
  events: TrackEvent[]
  zones: ZoneDefinition[]
  now: number
  onClose: () => void
  onCenter: () => void
}) {
  const speedMps = Math.hypot(track.vx, track.vy)
  const speedKnots = speedMps * 1.943844
  const heading = headingDeg(track.vx, track.vy)
  const trackEvents = events.filter(event => event.trackId === id).sort((a, b) => b.timestampMs - a.timestampMs)

  const zoneStates = zones.map(zone => {
    const latest = trackEvents.find(event => event.zoneId === zone.zoneId)
    const state = !latest || latest.type === 'ZONE_EXIT' ? 'CLEAR' : latest.newState
    return { zone, state }
  })

  return (
    <section className="rail-section inspector">
      <div className="rail-title-row">
        <div className="rail-title">TRACK INSPECTOR</div>
        <button className="icon-button" onClick={onClose} aria-label="Close inspector"><X size={15} /></button>
      </div>

      <div className="track-title-row">
        <Plane size={18} color={TRACK_COLORS[track.state]} />
        <span className="track-title mono">{id}</span>
        <StatePill state={track.state} />
      </div>

      <div className="inspector-grid">
        <InfoRow label="Ground speed" value={`${speedMps.toFixed(1)} m/s Â· ${Math.round(speedKnots)} kt`} />
        <InfoRow label="Heading" value={`${Math.round(heading)}Â°`} />
        <InfoRow label="Coordinates" value={`${track.py.toFixed(5)}, ${track.px.toFixed(5)}`} />
        <InfoRow label="Position uncertainty" value={`${track.uncertainty.toFixed(1)} m`} />
        <InfoRow label="Ellipse major" value={track.ellipseMajor != null ? `${track.ellipseMajor.toFixed(1)} m` : 'â€”'} />
        <InfoRow label="Ellipse minor" value={track.ellipseMinor != null ? `${track.ellipseMinor.toFixed(1)} m` : 'â€”'} />
        <InfoRow label="Last update" value={`${fmtTime(track.lastUpdateMs)} Â· ${fmtAge(now - track.lastUpdateMs)}`} />
        <InfoRow label="Sensor sources" value={track.contributingSensors?.join(', ') || 'â€”'} />
      </div>

      <div className="subsection-title">GEOFENCE STATUS</div>
      <div className="zone-state-grid">
        {zoneStates.map(({ zone, state }) => (
          <div className="zone-state-row" key={zone.zoneId}>
            <span className="zone-color-dot" style={{ background: zone.color }} />
            <span>{zone.zoneId}</span>
            <strong className={state === 'CLEAR' ? 'ok-text' : 'alert-text'}>{state}</strong>
          </div>
        ))}
      </div>

      <button className="primary-action" onClick={onCenter}><Crosshair size={15} /> CENTER ON TRACK</button>

      <div className="subsection-title">TRACK EVENTS</div>
      <div className="mini-event-list">
        {trackEvents.slice(0, 6).map(event => <MiniEvent key={event.eventId} event={event} />)}
        {trackEvents.length === 0 && <div className="muted-copy">No zone transitions for this track.</div>}
      </div>
    </section>
  )
}

function MissionSummary({
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

function RecentEvents({ events }: { events: TrackEvent[] }) {
  const recent = [...events].sort((a, b) => b.timestampMs - a.timestampMs).slice(0, 8)
  return (
    <section className="rail-section">
      <div className="rail-title-row">
        <div className="rail-title">RECENT EVENTS</div>
        <span className="rail-count">{events.length} session</span>
      </div>

      <div className="mini-event-list">
        {recent.map(event => <MiniEvent key={event.eventId} event={event} />)}
        {recent.length === 0 && <div className="muted-copy">Waiting for geofence transitions.</div>}
      </div>
    </section>
  )
}

function SensorStatus({ tracks, connected }: { tracks: Array<[string, FusedTrack]>; connected: boolean }) {
  return (
    <section className="rail-section rail-fill">
      <div className="rail-title">SENSOR STATUS</div>
      <table className="sensor-mini-table">
        <thead><tr><th>SENSOR</th><th>TRACKS</th><th>STATUS</th></tr></thead>
        <tbody>
          {SENSORS.map(sensor => {
            const count = tracks.filter(([, track]) => track.contributingSensors?.includes(sensor.id)).length
            return (
              <tr key={sensor.id}>
                <td className="mono">{sensor.id}</td>
                <td>{count}</td>
                <td>
                  <span className={`sensor-state ${connected ? count ? 'online' : 'idle' : 'offline'}`}>
                    {connected ? count ? 'ACTIVE' : 'IDLE' : 'OFFLINE'}
                  </span>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      <div className="benchmark-snapshot">
        <div className="rail-title-row">
          <div className="rail-title">BENCHMARK SNAPSHOT</div>
          <span className="rail-count">{BENCHMARK.date}</span>
        </div>

        <div className="snapshot-grid">
          <SummaryStat label="RMSE" value={`${BENCHMARK.positionRmse.toFixed(1)} m`} />
          <SummaryStat label="ASSOC." value={`${BENCHMARK.association}%`} />
          <SummaryStat label="200 TARGETS" value={`${(BENCHMARK.throughput[200] / 1000).toFixed(1)}K/s`} />
          <SummaryStat label="VIRTUAL P99" value={`${BENCHMARK.virtualLatency.p99.toFixed(1)} ms`} />
        </div>

        <div className="snapshot-note">Measured benchmark Â· not live telemetry</div>
      </div>
    </section>
  )
}

function EventFeed({ events }: { events: TrackEvent[] }) {
  const recent = [...events].sort((a, b) => b.timestampMs - a.timestampMs).slice(0, 7)
  return (
    <div className="event-feed">
      <div className="bottom-title-row">
        <span>EVENT FEED</span>
        <span>{events.length} SESSION EVENTS</span>
      </div>

      <div className="event-feed-header">
        <span>TIME</span><span>TYPE</span><span>TRACK</span><span>ZONE / TRANSITION</span>
      </div>

      {recent.map(event => (
        <div className="event-feed-row" key={event.eventId}>
          <span className="mono">{fmtTime(event.timestampMs)}</span>
          <span><EventPill type={event.type} /></span>
          <span className="mono strong">{event.trackId}</span>
          <span>{event.zoneId} Â· {event.previousState} â†’ {event.newState}</span>
        </div>
      ))}

      {recent.length === 0 && <div className="event-feed-empty">No spatial events received yet.</div>}
    </div>
  )
}

function ServiceBadge({ service }: { service: ServiceInfo }) {
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

function HeaderStat({ label, value, alert = false }: { label: string; value: string; alert?: boolean }) {
  return <div className="header-stat"><span>{label}</span><strong className={alert ? 'alert-text' : ''}>{value}</strong></div>
}

function MetricCard({ label, value, unit, history }: { label: string; value: string; unit: string; history: number[] }) {
  return (
    <div className="metric-card">
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      <div className="metric-unit">{unit}</div>
      <Sparkline values={history} />
    </div>
  )
}

function Sparkline({ values }: { values: number[] }) {
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

function MiniEvent({ event }: { event: TrackEvent }) {
  const colorClass = event.type === 'ZONE_ENTRY' ? 'entry' : event.type === 'ZONE_APPROACH' ? 'approach' : 'exit'
  return (
    <div className="mini-event">
      <span className={`mini-event-dot ${colorClass}`} />
      <div className="mini-event-time mono">{fmtTime(event.timestampMs)}</div>
      <div className="mini-event-copy">
        <strong className={colorClass}>{event.type.replace(/_/g, ' ')}</strong>
        <span>{event.trackId} Â· {event.zoneId}</span>
      </div>
    </div>
  )
}

function EventPill({ type }: { type: TrackEvent['type'] }) {
  return <span className={`event-pill ${type.toLowerCase()}`}>{type.replace(/_/g, ' ')}</span>
}

function StatePill({ state }: { state: FusedTrack['state'] }) {
  return <span className={`state-pill ${state.toLowerCase()}`}>{state}</span>
}

function StatusPill({ state }: { state: ServiceState }) {
  return <span className={`status-pill ${state.toLowerCase()}`}>{state}</span>
}

function MapToggle({
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

function LegendDot({ color, label }: { color: string; label: string }) {
  return <div className="legend-row"><span className="legend-line" style={{ background: color }} />{label}</div>
}

function TabShell({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
  return (
    <div className="tab-shell">
      <div className="tab-heading">
        <div><h2>{title}</h2><p>{subtitle}</p></div>
      </div>
      {children}
    </div>
  )
}

function BigMetric({
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

function BenchmarkPanel({ title, children }: { title: string; children: React.ReactNode }) {
  return <div className="benchmark-panel"><div className="benchmark-panel-title">{title}</div>{children}</div>
}

function TrendPanel({ label, values, unit }: { label: string; values: number[]; unit: string }) {
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

function SummaryStat({ label, value }: { label: string; value: string }) {
  return <div className="summary-stat"><strong>{value}</strong><span>{label}</span></div>
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return <div className="info-row"><span>{label}</span><strong>{value}</strong></div>
}

function TransportRow({ label, connected }: { label: string; connected: boolean }) {
  return (
    <div className="transport-row">
      <Wifi size={15} />
      <span>{label}</span>
      <strong className={connected ? 'ok-text' : 'alert-text'}>{connected ? 'CONNECTED' : 'OFFLINE'}</strong>
    </div>
  )
}

function EmptyState({ text }: { text: string }) {
  return <div className="empty-state">{text}</div>
}

function syncZoneLayers(map: maplibregl.Map, zones: ZoneDefinition[], visible: boolean) {
  const features = zones.flatMap(zone => [
    zoneFeature(zone.advisory, zone, 'advisory'),
    zoneFeature(zone.warning, zone, 'warning'),
    zoneFeature(zone.core, zone, 'core'),
  ])

  const data = {
    type: 'FeatureCollection',
    features,
  } as any

  const existing = map.getSource('backend-zones') as maplibregl.GeoJSONSource | undefined
  if (existing) {
    existing.setData(data)
  } else {
    map.addSource('backend-zones', { type: 'geojson', data })

    map.addLayer({
      id: 'zones-core-fill',
      type: 'fill',
      source: 'backend-zones',
      filter: ['==', ['get', 'level'], 'core'],
      paint: {
        'fill-color': ['get', 'color'],
        'fill-opacity': 0.16,
      },
    } as any)

    map.addLayer({
      id: 'zones-advisory',
      type: 'line',
      source: 'backend-zones',
      filter: ['==', ['get', 'level'], 'advisory'],
      paint: {
        'line-color': ['get', 'color'],
        'line-width': 1.5,
        'line-opacity': 0.45,
        'line-dasharray': [4, 3],
      },
    } as any)

    map.addLayer({
      id: 'zones-warning',
      type: 'line',
      source: 'backend-zones',
      filter: ['==', ['get', 'level'], 'warning'],
      paint: {
        'line-color': ['get', 'color'],
        'line-width': 1.8,
        'line-opacity': 0.68,
        'line-dasharray': [5, 3],
      },
    } as any)

    map.addLayer({
      id: 'zones-core-line',
      type: 'line',
      source: 'backend-zones',
      filter: ['==', ['get', 'level'], 'core'],
      paint: {
        'line-color': ['get', 'color'],
        'line-width': 2.4,
        'line-opacity': 0.94,
        'line-dasharray': [5, 3],
      },
    } as any)
  }

  for (const layerId of ['zones-core-fill', 'zones-advisory', 'zones-warning', 'zones-core-line']) {
    if (map.getLayer(layerId)) {
      map.setLayoutProperty(layerId, 'visibility', visible ? 'visible' : 'none')
    }
  }
}

function zoneFeature(
  coordinates: [number, number][],
  zone: ZoneDefinition,
  level: 'core' | 'warning' | 'advisory',
) {
  return {
    type: 'Feature',
    properties: {
      zoneId: zone.zoneId,
      label: zone.label,
      color: zone.color,
      level,
    },
    geometry: {
      type: 'Polygon',
      coordinates: [coordinates],
    },
  }
}

function appendHistory(values: number[], next: number) {
  return [...values.slice(-39), Number.isFinite(next) ? next : 0]
}

function validLngLat(lng: number, lat: number) {
  return Number.isFinite(lng) && Number.isFinite(lat) && Math.abs(lng) <= 180 && Math.abs(lat) <= 90
}

function stateRank(state: FusedTrack['state']) {
  return state === 'CONFIRMED' ? 0 : state === 'COASTING' ? 1 : state === 'TENTATIVE' ? 2 : 3
}

function headingDeg(vx: number, vy: number) {
  return (Math.atan2(vx, vy) * 180 / Math.PI + 360) % 360
}

function fmtTime(ms: number) {
  if (!Number.isFinite(ms) || ms <= 0) return 'â€”'
  return new Date(ms).toISOString().slice(11, 19) + 'Z'
}

function fmtAge(ms: number) {
  if (!Number.isFinite(ms)) return 'â€”'
  if (ms < 1_000) return `${Math.max(0, ms)} ms ago`
  if (ms < 60_000) return `${(ms / 1_000).toFixed(1)} s ago`
  return `${Math.floor(ms / 60_000)}m ago`
}

function fmtDuration(ms: number) {
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

function fmtCompact(value: number) {
  if (!Number.isFinite(value)) return '0'
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (Math.abs(value) >= 1_000) return `${(value / 1_000).toFixed(1)}K`
  return Math.round(value).toLocaleString()
}

ReactDOM.createRoot(document.getElementById('root')!).render(<App />)




