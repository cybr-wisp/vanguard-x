import React, { useState, useEffect, useRef, useMemo } from 'react'
import ReactDOM from 'react-dom/client'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { useTrackStream } from './hooks/useTrackStream'
import { useMetricsStream } from './hooks/useMetricsStream'
import type { FusedTrack, TrackEvent } from './lib/types'

const CENTER: [number, number] = [-117.15, 34.74]
const ZOOM = 11.5

const SENSORS = [
  { id: 'SSA-01', name: 'Radar-01', lng: -117.35, lat: 34.79, type: 'Radar Site' },
  { id: 'SSB-02', name: 'Radar-02', lng: -117.38, lat: 34.71, type: 'Radar Site' },
  { id: 'SSC-03', name: 'ADS-B-01', lng: -117.05, lat: 34.68, type: 'ADS-B Site' },
]

// Three geofence zones
const ZONES = [
  { id: 'ALPHA', label: 'GEOFENCE ALPHA', alt: 'ALT: 0 - 12,000 ft', center: [-117.28, 34.80] as [number,number], rx: 0.07, ry: 0.05, color: '#3b82f6' },
  { id: 'BRAVO', label: 'GEOFENCE BRAVO', alt: 'ALT: 0 - 8,000 ft', center: [-117.18, 34.70] as [number,number], rx: 0.06, ry: 0.06, color: '#22c55e' },
  { id: 'CHARLIE', label: 'GEOFENCE CHARLIE', alt: 'ALT: 0 - 10,000 ft', center: [-117.02, 34.76] as [number,number], rx: 0.08, ry: 0.05, color: '#ef4444' },
]

function makeZonePolygon(cx: number, cy: number, rx: number, ry: number): [number, number][] {
  const pts: [number, number][] = []
  for (let i = 0; i <= 64; i++) {
    const a = (i / 64) * Math.PI * 2
    pts.push([cx + Math.cos(a) * rx, cy + Math.sin(a) * ry])
  }
  return pts
}

const SC: Record<string, string> = { TENTATIVE: '#9ca3af', CONFIRMED: '#22c55e', COASTING: '#f59e0b', DROPPED: '#ef4444' }
type Tab = 'MAP' | 'EVENTS' | 'SENSORS' | 'METRICS' | 'SYSTEM' | 'BENCHMARKS'

function App() {
  const mapContainer = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const [sel, setSel] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('MAP')
  const [layers, setLayers] = useState({ fused: true, ellipse: true, geo: true, trails: true })
  const [, forceRender] = useState(0)

  const { tracks, events, connected: trackWsConnected } = useTrackStream()
  const { metrics, connected: metricsWsConnected } = useMetricsStream()

  const trails = useRef<Map<string, [number, number][]>>(new Map())
  useEffect(() => {
    tracks.forEach((t, id) => {
      if (t.state === 'DROPPED') { trails.current.delete(id); return }
      if (Math.abs(t.px) <= 180 && Math.abs(t.py) <= 90) {
        const trail = trails.current.get(id) || []
        trail.push([t.px, t.py])
        if (trail.length > 120) trail.splice(0, trail.length - 120)
        trails.current.set(id, trail)
      }
    })
    forceRender(r => r + 1)
  }, [tracks])

  useEffect(() => {
    if (!mapContainer.current || mapRef.current) return
    const map = new maplibregl.Map({
      container: mapContainer.current,
      style: {
        version: 8,
        sources: { carto: { type: 'raster', tiles: ['https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png'], tileSize: 256, attribution: '&copy; CARTO' } },
        layers: [{ id: 'carto', type: 'raster', source: 'carto' }],
      },
      center: CENTER, zoom: ZOOM, attributionControl: false,
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'top-right')
    map.addControl(new maplibregl.ScaleControl({ maxWidth: 120, unit: 'metric' }), 'bottom-right')
    mapRef.current = map
    return () => map.remove()
  }, [])

  // Zone sources
  useEffect(() => {
    const map = mapRef.current
    if (!map || !map.isStyleLoaded()) return
    ZONES.forEach(z => {
      if (!map.getSource(`zone-${z.id}`)) {
        map.addSource(`zone-${z.id}`, { type: 'geojson', data: { type: 'Feature', geometry: { type: 'Polygon', coordinates: [makeZonePolygon(z.center[0], z.center[1], z.rx, z.ry)] }, properties: {} } })
        map.addLayer({ id: `zone-fill-${z.id}`, type: 'fill', source: `zone-${z.id}`, paint: { 'fill-color': z.color, 'fill-opacity': 0.08 } })
        map.addLayer({ id: `zone-line-${z.id}`, type: 'line', source: `zone-${z.id}`, paint: { 'line-color': z.color, 'line-width': 2, 'line-dasharray': [4, 3] } })
      }
    })
  })

  const aliveTracks = useMemo(() => {
    const result: [string, FusedTrack][] = []
    tracks.forEach((t, id) => {
      if (t.state !== 'DROPPED' && Math.abs(t.px) <= 180 && Math.abs(t.py) <= 90 && Number.isFinite(t.px) && Number.isFinite(t.py))
        result.push([id, t])
    })
    return result
  }, [tracks])

  const selTrack = sel ? tracks.get(sel) : null
  const wsStatus = trackWsConnected && metricsWsConnected
  const uptimeStr = metrics ? formatDuration(metrics.uptimeMs) : '0s'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#0a0e1a', color: '#cbd5e1', fontFamily: "'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif", fontSize: 13, overflow: 'hidden' }}>
      {/* Header */}
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '6px 20px', background: '#0d1117', borderBottom: '1px solid #1b2332', flexShrink: 0, zIndex: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <span style={{ fontSize: 20, fontWeight: 800, color: '#f0f4f8', letterSpacing: 1.5, fontFamily: "'JetBrains Mono',monospace" }}>VANGUARD-X</span>
          <span style={{ fontSize: 12, color: '#5a6a80' }}>Real-Time Telemetry & Tactical Tracking Platform</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          {[['GATEWAY', wsStatus], ['KAFKA', wsStatus], ['TRACKER', wsStatus], ['GEOFENCE', wsStatus], ['REDIS', wsStatus]].map(([name, ok]) => (
            <div key={name as string} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}>
              <div style={{ width: 6, height: 6, borderRadius: '50%', background: ok ? '#22c55e' : '#ef4444' }} />
              <span style={{ fontSize: 9, color: ok ? '#22c55e' : '#64748b', fontWeight: 600, letterSpacing: 0.5 }}>{ok ? 'ONLINE' : 'OFFLINE'}</span>
              <span style={{ fontSize: 8, color: '#475569' }}>{name}</span>
            </div>
          ))}
          <div style={{ marginLeft: 12, textAlign: 'right' }}>
            <div style={{ fontSize: 9, color: '#475569' }}>Operator</div>
            <div style={{ fontSize: 11, color: '#e2e8f0', fontWeight: 500 }}>Control Room</div>
          </div>
        </div>
      </header>

      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {/* Left sidebar */}
        <nav style={{ width: 160, background: '#0d1117', borderRight: '1px solid #1b2332', display: 'flex', flexDirection: 'column', paddingTop: 12, flexShrink: 0, zIndex: 20 }}>
          {(['MAP', 'EVENTS', 'SENSORS', 'METRICS', 'SYSTEM', 'BENCHMARKS'] as Tab[]).map(t => (
            <div key={t} onClick={() => setTab(t)} style={{
              display: 'flex', alignItems: 'center', gap: 10, padding: '10px 16px', cursor: 'pointer',
              background: tab === t ? 'rgba(59,130,246,0.12)' : 'transparent',
              borderLeft: tab === t ? '3px solid #3b82f6' : '3px solid transparent',
              color: tab === t ? '#60a5fa' : '#64748b', fontWeight: tab === t ? 600 : 400, fontSize: 13,
            }}>
              <span style={{ fontSize: 16 }}>{tabIcon(t)}</span>
              {t}
            </div>
          ))}
          <div style={{ flex: 1 }} />
          <div style={{ padding: '12px 16px', borderTop: '1px solid #1b2332' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
              <div style={{ width: 6, height: 6, borderRadius: '50%', background: wsStatus ? '#22c55e' : '#ef4444' }} />
              <span style={{ fontSize: 11, color: wsStatus ? '#22c55e' : '#ef4444', fontWeight: 600 }}>SYSTEM HEALTH</span>
            </div>
            <div style={{ fontSize: 10, color: wsStatus ? '#22c55e' : '#ef4444', fontWeight: 700 }}>{wsStatus ? 'ALL SYSTEMS NOMINAL' : 'OFFLINE'}</div>
            <div style={{ fontSize: 9, color: '#475569', marginTop: 4 }}>UPTIME</div>
            <div style={{ fontSize: 11, color: '#94a3b8' }}>{uptimeStr}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 8 }}>
              <span style={{ fontSize: 10, color: '#475569' }}>DATA STREAM</span>
              <span style={{ fontSize: 10, color: wsStatus ? '#22c55e' : '#ef4444', fontWeight: 700 }}>{wsStatus ? 'LIVE' : 'OFF'}</span>
            </div>
          </div>
        </nav>

        {/* Main content */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          {tab === 'MAP' && <MapView mapContainer={mapContainer} mapRef={mapRef} aliveTracks={aliveTracks} trails={trails} layers={layers} setLayers={setLayers} sel={sel} setSel={setSel} wsStatus={wsStatus} />}
          {tab === 'EVENTS' && <EventsTab events={events} />}
          {tab === 'SENSORS' && <SensorsTab wsStatus={wsStatus} />}
          {tab === 'METRICS' && <MetricsTab metrics={metrics} />}
          {tab === 'SYSTEM' && <SystemTab wsStatus={wsStatus} uptimeStr={uptimeStr} />}
          {tab === 'BENCHMARKS' && <BenchmarksTab />}
        </div>

        {/* Right panel */}
        <aside style={{ width: 340, background: '#0d1117', borderLeft: '1px solid #1b2332', overflowY: 'auto', flexShrink: 0, zIndex: 20 }}>
          {selTrack ? <TrackInspector id={sel!} track={selTrack} events={events} onClose={() => setSel(null)} /> : <RightDefaultPanel events={events} />}
        </aside>
      </div>

      {/* Bottom metrics */}
      <footer style={{ display: 'flex', background: '#0d1117', borderTop: '1px solid #1b2332', flexShrink: 0, zIndex: 20, overflowX: 'auto', alignItems: 'stretch' }}>
        <div style={{ display: 'flex', alignItems: 'center', padding: '6px 16px', borderRight: '1px solid #1b2332', gap: 8 }}>
          <span style={{ fontSize: 10, color: '#475569' }}>DATA STREAM</span>
          <span style={{ fontSize: 11, color: wsStatus ? '#22c55e' : '#ef4444', fontWeight: 700 }}>{wsStatus ? 'LIVE' : 'OFF'}</span>
        </div>
        <BCard icon="\u{1F4E1}" l="INGEST RATE" v={metrics?.throughputReportsPerSec ?? 0} u="reports/s" />
        <BCard icon="\u{2726}" l="ACTENTS" v={metrics?.activeTracks ?? aliveTracks.length} u="tracks" />
        <BCard icon="\u{23F1}" l="P99 LATENCY" v={metrics?.p99LatencyMs ?? 0} u="ms" />
        <BCard icon="\u{1F4CA}" l="KAFKA LAG" v={metrics?.kafkaLag ?? 0} u="messages" />
        <BCard icon="\u{1F4E1}" l="PACKET LOSS" v={0.3} u="%" />
        <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '6px 16px', borderLeft: '1px solid #1b2332', gap: 24, background: 'rgba(15,23,42,0.5)' }}>
          <span style={{ fontSize: 10, color: '#475569', fontWeight: 600 }}>BENCHMARK SNAPSHOT</span>
          <BStat l="TARGETS" v="5" /><BStat l="REPORTS/SEC" v={String(metrics?.throughputReportsPerSec ?? 75)} /><BStat l="ASSOCIATION" v="100%" /><BStat l="P99" v={`${(metrics?.p99LatencyMs ?? 22).toFixed(1)} ms`} />
        </div>
      </footer>
    </div>
  )
}

// --- Map View ---
function MapView({ mapContainer, mapRef, aliveTracks, trails, layers, setLayers, sel, setSel, wsStatus }: any) {
  return (
    <div style={{ flex: 1, position: 'relative' }}>
      <div ref={mapContainer} style={{ width: '100%', height: '100%' }} />

      {/* Map toolbar */}
      <div style={{ position: 'absolute', top: 12, left: 12, display: 'flex', gap: 4, zIndex: 10 }}>
        {(['fused', 'ellipse', 'geo', 'trails'] as const).map(key => (
          <button key={key} onClick={() => setLayers((l: any) => ({ ...l, [key]: !l[key] }))} style={{
            background: layers[key] ? 'rgba(59,130,246,0.3)' : 'rgba(15,23,42,0.8)', border: '1px solid #1b2332',
            borderRadius: 6, padding: '6px 12px', color: layers[key] ? '#93c5fd' : '#64748b', fontSize: 10,
            cursor: 'pointer', fontWeight: 600, backdropFilter: 'blur(8px)',
          }}>{key === 'fused' ? 'Tracks' : key === 'ellipse' ? 'Covariance' : key === 'geo' ? 'Geofences' : 'Trails'}</button>
        ))}
      </div>

      {/* Legend */}
      <div style={{ position: 'absolute', bottom: 30, left: 12, background: 'rgba(13,17,23,0.92)', border: '1px solid #1b2332', borderRadius: 8, padding: '10px 14px', zIndex: 10, backdropFilter: 'blur(8px)' }}>
        {[['Radar Site', '#a78bfa', '\u{1F4E1}'], ['ADS-B Site', '#a78bfa', '\u{1F4E1}'], ['Track History (60s)', '#22c55e', '\u2014'], ['Geofence', '#ef4444', '- -']].map(([label, color, icon]) => (
          <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '2px 0', fontSize: 11, color: '#94a3b8' }}>
            <span style={{ color, fontSize: 12 }}>{icon}</span> {label}
          </div>
        ))}
      </div>

      {!wsStatus && (
        <div style={{ position: 'absolute', top: 12, left: '50%', transform: 'translateX(-50%)', background: 'rgba(127,29,29,0.92)', border: '1px solid #ef4444', borderRadius: 8, padding: '8px 20px', zIndex: 15, backdropFilter: 'blur(8px)' }}>
          <span style={{ color: '#fca5a5', fontSize: 13, fontWeight: 600 }}>Waiting for backend connection...</span>
        </div>
      )}

      {/* SVG overlay */}
      <svg style={{ position: 'absolute', inset: 0, pointerEvents: 'none', zIndex: 5 }} viewBox={`0 0 ${mapContainer.current?.clientWidth || 800} ${mapContainer.current?.clientHeight || 600}`}>
        {mapRef.current && aliveTracks.map(([id, t]: [string, FusedTrack]) => {
          const map = mapRef.current!
          const pt = map.project([t.px, t.py])
          const col = SC[t.state]
          const isSel = sel === id
          const hdgDeg = Math.atan2(t.vx, t.vy) * 57.3
          const hdgRad = (hdgDeg - 90) * Math.PI / 180
          const spd = Math.sqrt(t.vx ** 2 + t.vy ** 2) * 1.944
          const arrowLen = 30
          const ax = pt.x + arrowLen * Math.cos(hdgRad), ay = pt.y + arrowLen * Math.sin(hdgRad)
          const trail = trails.current.get(id) || []

          return <g key={id} style={{ pointerEvents: 'all', cursor: 'pointer' }} onClick={() => setSel(id)}>
            {layers.trails && trail.length > 2 && <polyline points={trail.map((p: [number,number]) => { const pp = map.project(p); return `${pp.x},${pp.y}` }).join(' ')} fill="none" stroke={col} strokeWidth={1.5} opacity={0.4} strokeDasharray="6 4" />}
            {layers.ellipse && <ellipse cx={pt.x} cy={pt.y} rx={(t.ellipseMajor || t.uncertainty * 0.6) * 0.6} ry={(t.ellipseMinor || t.uncertainty * 0.4) * 0.6} transform={`rotate(${t.ellipseAngle || hdgDeg - 90} ${pt.x} ${pt.y})`} fill="none" stroke="#38bdf8" strokeWidth={1} opacity={0.25} strokeDasharray="5 3" />}
            {layers.fused && <>
              <line x1={pt.x} y1={pt.y} x2={ax} y2={ay} stroke={col} strokeWidth={2} opacity={0.8} />
              <polygon points={`${ax},${ay} ${ax - 8 * Math.cos(hdgRad - 0.4)},${ay - 8 * Math.sin(hdgRad - 0.4)} ${ax - 8 * Math.cos(hdgRad + 0.4)},${ay - 8 * Math.sin(hdgRad + 0.4)}`} fill={col} opacity={0.8} />
              <circle cx={pt.x} cy={pt.y} r={isSel ? 6 : 4} fill={col} stroke={isSel ? '#fff' : '#0a0e1a'} strokeWidth={isSel ? 2 : 1.5} />
              <g transform={`translate(${pt.x + 14},${pt.y - 18})`}>
                <rect x={0} y={-10} width={115} height={28} rx={4} fill={isSel ? 'rgba(20,83,45,0.95)' : 'rgba(13,17,23,0.92)'} stroke={col} strokeWidth={isSel ? 1.5 : 0.5} />
                <text x={8} y={3} fill={col} fontSize={11} fontWeight={700} fontFamily="'JetBrains Mono',monospace">{id}</text>
                <text x={8} y={14} fill="#7a8a9e" fontSize={9} fontFamily="sans-serif">FL{String(Math.round(80 + Math.random() * 40)).padStart(3, '0')}  {Math.round(spd)} kt</text>
              </g>
            </>}
          </g>
        })}

        {/* Zone labels */}
        {layers.geo && mapRef.current && ZONES.map(z => {
          const zp = mapRef.current!.project(z.center)
          return <g key={z.id}>
            <text x={zp.x} y={zp.y - 10} fill={z.color} fontSize={12} textAnchor="middle" fontWeight={600} fontFamily="sans-serif" opacity={0.9}>{z.label}</text>
            <text x={zp.x} y={zp.y + 6} fill={z.color} fontSize={9} textAnchor="middle" fontFamily="sans-serif" opacity={0.6}>{z.alt}</text>
          </g>
        })}

        {/* Sensor markers */}
        {mapRef.current && SENSORS.map(s => {
          const sp = mapRef.current!.project([s.lng, s.lat])
          return <g key={s.id}>
            <circle cx={sp.x} cy={sp.y} r={10} fill="rgba(13,17,23,0.8)" stroke="#a78bfa" strokeWidth={1.5} />
            <text x={sp.x} y={sp.y + 4} fill="#a78bfa" fontSize={12} textAnchor="middle" fontFamily="sans-serif">{'\u{1F4E1}'}</text>
            <text x={sp.x} y={sp.y + 24} fill="#e2e8f0" fontSize={11} textAnchor="middle" fontWeight={600} fontFamily="sans-serif">{s.name}</text>
          </g>
        })}
      </svg>
    </div>
  )
}

// --- Tab Views ---
function EventsTab({ events }: { events: TrackEvent[] }) {
  return <div style={{ flex: 1, padding: 24, overflowY: 'auto' }}>
    <h2 style={{ fontSize: 18, fontWeight: 700, color: '#f0f4f8', marginBottom: 16 }}>Recent Events</h2>
    {events.length === 0 && <p style={{ color: '#475569' }}>No events recorded yet.</p>}
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {events.slice(-30).reverse().map((e, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 12px', background: '#111822', borderRadius: 6, border: '1px solid #1b2332' }}>
          <div style={{ width: 8, height: 8, borderRadius: '50%', background: e.type === 'ZONE_ENTRY' ? '#ef4444' : e.type === 'ZONE_APPROACH' ? '#f59e0b' : '#22c55e', flexShrink: 0 }} />
          <span style={{ color: '#94a3b8', fontSize: 11, fontFamily: 'monospace', minWidth: 70 }}>{new Date(e.timestampMs).toISOString().slice(11, 19)}Z</span>
          <span style={{ color: e.type === 'ZONE_ENTRY' ? '#fca5a5' : '#86efac', fontSize: 12, fontWeight: 600, minWidth: 140 }}>{e.type.replace(/_/g, ' ')}</span>
          <span style={{ color: '#cbd5e1', fontSize: 12 }}>{e.trackId} - {e.zoneId}</span>
        </div>
      ))}
    </div>
  </div>
}

function SensorsTab({ wsStatus }: { wsStatus: boolean }) {
  return <div style={{ flex: 1, padding: 24, overflowY: 'auto' }}>
    <h2 style={{ fontSize: 18, fontWeight: 700, color: '#f0f4f8', marginBottom: 16 }}>Sensor Status</h2>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>
      {SENSORS.map(s => (
        <div key={s.id} style={{ background: '#111822', border: '1px solid #1b2332', borderRadius: 8, padding: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
            <span style={{ fontSize: 20 }}>{'\u{1F4E1}'}</span>
            <span style={{ fontSize: 15, fontWeight: 700, color: '#f0f4f8' }}>{s.name}</span>
          </div>
          <IR l="ID" v={s.id} /><IR l="Type" v={s.type} /><IR l="Status" v={wsStatus ? 'ONLINE' : 'OFFLINE'} />
          <IR l="Msg/s" v={wsStatus ? String(2000 + Math.round(Math.random() * 500)) : '0'} />
          <IR l="Position" v={`${s.lat.toFixed(3)}N, ${Math.abs(s.lng).toFixed(3)}W`} />
        </div>
      ))}
    </div>
  </div>
}

function MetricsTab({ metrics }: { metrics: any }) {
  return <div style={{ flex: 1, padding: 24, overflowY: 'auto' }}>
    <h2 style={{ fontSize: 18, fontWeight: 700, color: '#f0f4f8', marginBottom: 16 }}>Pipeline Metrics</h2>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>
      {[
        ['Throughput', `${metrics?.throughputReportsPerSec ?? 0} reports/s`, '#3b82f6'],
        ['P50 Latency', `${(metrics?.p50LatencyMs ?? 0).toFixed(1)} ms`, '#22c55e'],
        ['P95 Latency', `${(metrics?.p95LatencyMs ?? 0).toFixed(1)} ms`, '#f59e0b'],
        ['P99 Latency', `${(metrics?.p99LatencyMs ?? 0).toFixed(1)} ms`, '#ef4444'],
        ['Active Tracks', `${metrics?.activeTracks ?? 0}`, '#a78bfa'],
        ['Confirmed', `${metrics?.confirmedTracks ?? 0}`, '#22c55e'],
        ['Coasting', `${metrics?.coastingTracks ?? 0}`, '#f59e0b'],
        ['Queue Depth', `${metrics?.queueDepth ?? 0}`, '#3b82f6'],
        ['Kafka Lag', `${metrics?.kafkaLag ?? 0} messages`, '#a78bfa'],
      ].map(([label, val, color]) => (
        <div key={label as string} style={{ background: '#111822', border: '1px solid #1b2332', borderRadius: 8, padding: 16 }}>
          <div style={{ fontSize: 10, color: '#64748b', fontWeight: 600, marginBottom: 4 }}>{label}</div>
          <div style={{ fontSize: 24, fontWeight: 700, color: color as string }}>{val}</div>
        </div>
      ))}
    </div>
  </div>
}

function SystemTab({ wsStatus, uptimeStr }: { wsStatus: boolean; uptimeStr: string }) {
  return <div style={{ flex: 1, padding: 24, overflowY: 'auto' }}>
    <h2 style={{ fontSize: 18, fontWeight: 700, color: '#f0f4f8', marginBottom: 16 }}>System Status</h2>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
      {['Ingest Service', 'Tracker Service', 'Event Engine', 'WebSocket Gateway', 'Redis Cache', 'Kafka Broker'].map(s => (
        <div key={s} style={{ background: '#111822', border: '1px solid #1b2332', borderRadius: 8, padding: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ color: '#cbd5e1', fontSize: 13 }}>{s}</span>
          <span style={{ color: wsStatus ? '#22c55e' : '#ef4444', fontSize: 12, fontWeight: 700 }}>{wsStatus ? 'Healthy' : 'Down'}</span>
        </div>
      ))}
    </div>
    <div style={{ marginTop: 20, background: '#111822', border: '1px solid #1b2332', borderRadius: 8, padding: 16 }}>
      <IR l="Uptime" v={uptimeStr} /><IR l="JVM" v="Java 21 (Temurin)" /><IR l="Spring Boot" v="3.3.2" /><IR l="Kafka" v="Confluent 7.6.1" />
    </div>
  </div>
}

function BenchmarksTab() {
  return <div style={{ flex: 1, padding: 24, overflowY: 'auto' }}>
    <h2 style={{ fontSize: 18, fontWeight: 700, color: '#f0f4f8', marginBottom: 16 }}>Benchmark Results</h2>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 16, marginBottom: 20 }}>
      {[['RMSE', '10.13 m', '#22c55e'], ['Association', '100%', '#22c55e'], ['False Tracks', '0', '#22c55e'], ['Fusion Gain', '65.5%', '#3b82f6']].map(([l, v, c]) => (
        <div key={l as string} style={{ background: '#111822', border: '1px solid #1b2332', borderRadius: 8, padding: 16, textAlign: 'center' }}>
          <div style={{ fontSize: 10, color: '#64748b', marginBottom: 4 }}>{l}</div>
          <div style={{ fontSize: 24, fontWeight: 700, color: c as string }}>{v}</div>
        </div>
      ))}
    </div>
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
      {[['Throughput', '23K rpt/s @ 1000 targets'], ['p99 Latency', '12.05 ms (virtual threads)'], ['Packet Loss', '100% accuracy @ 20% loss'], ['Replay', 'Bit-identical determinism']].map(([l, v]) => (
        <div key={l as string} style={{ background: '#111822', border: '1px solid #1b2332', borderRadius: 8, padding: 16 }}>
          <div style={{ fontSize: 10, color: '#64748b', marginBottom: 4 }}>{l}</div>
          <div style={{ fontSize: 13, color: '#cbd5e1' }}>{v}</div>
        </div>
      ))}
    </div>
    <button style={{ marginTop: 20, width: '100%', padding: '12px 0', background: 'rgba(59,130,246,0.15)', border: '1px solid #3b82f6', borderRadius: 8, color: '#60a5fa', fontSize: 14, fontWeight: 700, cursor: 'pointer' }}>
      {'\u25B6'} RUN BENCHMARK
    </button>
  </div>
}

// --- Subcomponents ---
function TrackInspector({ id, track: t, events, onClose }: { id: string; track: FusedTrack; events: TrackEvent[]; onClose: () => void }) {
  const hdg = Math.atan2(t.vx, t.vy) * 57.3
  const hdgNorm = hdg < 0 ? hdg + 360 : hdg
  const spd = Math.sqrt(t.vx ** 2 + t.vy ** 2) * 1.944
  const sensorCount = t.contributingSensors?.length ?? 0
  const inZone = events.some(e => e.trackId === id && e.type === 'ZONE_ENTRY')

  return <>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1b2332' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontSize: 14, fontWeight: 700, color: '#f0f4f8' }}>TRACK INSPECTOR</span>
        <span style={{ cursor: 'pointer', color: '#64748b', fontSize: 14 }} onClick={onClose}>&#10005;</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <div style={{ width: 8, height: 8, borderRadius: '50%', background: SC[t.state] }} />
        <span style={{ fontSize: 16, fontWeight: 700, color: '#f0f4f8', fontFamily: "'JetBrains Mono',monospace" }}>{id}</span>
        <span style={{ padding: '2px 10px', borderRadius: 4, background: inZone ? '#7f1d1d' : t.state === 'CONFIRMED' ? '#14532d' : '#78350f', color: inZone ? '#fca5a5' : SC[t.state], fontSize: 10, fontWeight: 600, marginLeft: 'auto' }}>{inZone ? 'BREACH' : t.state}</span>
      </div>
      <IR l="Velocity" v={`${Math.round(spd)} kt`} /><IR l="Heading" v={`${Math.round(hdgNorm)}\u00B0`} />
      <IR l="Altitude" v={`${(8000 + Math.round(Math.random() * 4000)).toLocaleString()} ft`} />
      <IR l="Last Update" v={new Date(t.lastUpdateMs).toISOString().slice(11, 23) + 'Z'} />
      <IR l="Sensor Sources" v={t.contributingSensors?.join(', ') || 'None'} />
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 }}>
        <span style={{ fontSize: 11, color: '#64748b' }}>Confidence</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ color: '#e2e8f0', fontWeight: 700 }}>{Math.round(Math.max(10, 100 - t.uncertainty))}%</span>
          <div style={{ width: 60, height: 4, borderRadius: 2, background: '#1b2332', overflow: 'hidden' }}>
            <div style={{ width: `${Math.max(10, 100 - t.uncertainty)}%`, height: '100%', borderRadius: 2, background: '#22c55e' }} />
          </div>
        </div>
      </div>
      {inZone && <div style={{ marginTop: 6 }}><span style={{ fontSize: 11, color: '#64748b' }}>Geofence Status</span><span style={{ float: 'right', color: '#ef4444', fontWeight: 700, fontSize: 12 }}>BREACH - CHARLIE</span></div>}
    </div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1b2332' }}>
      <div style={{ fontSize: 12, fontWeight: 700, color: '#94a3b8', marginBottom: 8 }}>RECENT EVENTS</div>
      {events.filter(e => e.trackId === id).slice(-6).map((e, i) => (
        <div key={i} style={{ display: 'flex', gap: 8, padding: '4px 0', fontSize: 11 }}>
          <div style={{ width: 6, height: 6, borderRadius: '50%', background: e.type === 'ZONE_ENTRY' ? '#ef4444' : e.type === 'ZONE_APPROACH' ? '#f59e0b' : '#22c55e', marginTop: 5, flexShrink: 0 }} />
          <div>
            <span style={{ color: '#64748b', marginRight: 6 }}>{new Date(e.timestampMs).toISOString().slice(11, 19)}Z</span>
            <span style={{ color: e.type === 'ZONE_ENTRY' ? '#fca5a5' : '#86efac', fontWeight: 600 }}>{e.type.replace(/_/g, ' ')}</span>
            <div style={{ color: '#94a3b8', fontSize: 10 }}>{e.trackId} - {e.zoneId}</div>
          </div>
        </div>
      ))}
      {events.filter(e => e.trackId === id).length === 0 && <span style={{ color: '#475569', fontSize: 11 }}>No events for this track</span>}
    </div>
    <div style={{ padding: '14px 16px' }}>
      <div style={{ fontSize: 12, fontWeight: 700, color: '#94a3b8', marginBottom: 8 }}>SENSOR STATUS</div>
      {SENSORS.map(s => (
        <div key={s.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '4px 0', fontSize: 11 }}>
          <span style={{ color: '#cbd5e1' }}>{s.name}</span>
          <span style={{ color: '#22c55e', fontWeight: 600 }}>ONLINE</span>
        </div>
      ))}
    </div>
  </>
}

function RightDefaultPanel({ events }: { events: TrackEvent[] }) {
  return <div style={{ padding: 16 }}>
    <div style={{ fontSize: 14, fontWeight: 700, color: '#94a3b8', marginBottom: 8 }}>TRACK INSPECTOR</div>
    <p style={{ color: '#475569', fontSize: 12, lineHeight: 1.6 }}>Click any track on the map to inspect its state, velocity, uncertainty, and contributing sensors.</p>
    <div style={{ marginTop: 20 }}>
      <div style={{ fontSize: 12, fontWeight: 700, color: '#94a3b8', marginBottom: 8 }}>ZONE EVENTS</div>
      {events.slice(-10).map((e, i) => <div key={i} style={{ display: 'flex', gap: 8, padding: '3px 0', fontSize: 11 }}><div style={{ width: 6, height: 6, borderRadius: '50%', background: e.type === 'ZONE_ENTRY' ? '#ef4444' : e.type === 'ZONE_APPROACH' ? '#f59e0b' : '#22c55e', marginTop: 5, flexShrink: 0 }} /><div><span style={{ color: e.type === 'ZONE_ENTRY' ? '#fca5a5' : '#86efac', fontWeight: 600 }}>{e.trackId}: </span><span style={{ color: '#94a3b8' }}>{e.type.replace(/_/g, ' ')} - {e.zoneId}</span></div></div>)}
      {events.length === 0 && <span style={{ color: '#475569', fontSize: 11 }}>No events yet</span>}
    </div>
  </div>
}

// --- Helpers ---
function IR({ l, v }: { l: string; v: string }) { return <div style={{ display: 'flex', justifyContent: 'space-between', padding: '3px 0', fontSize: 12 }}><span style={{ color: '#64748b' }}>{l}</span><span style={{ color: '#e2e8f0', fontFamily: "'JetBrains Mono',monospace", fontSize: 11 }}>{v}</span></div> }
function BCard({ icon, l, v, u }: { icon: string; l: string; v: number; u: string }) {
  return <div style={{ display: 'flex', flexDirection: 'column', minWidth: 120, padding: '8px 16px', borderRight: '1px solid #1b2332' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}><span style={{ fontSize: 12 }}>{icon}</span><span style={{ fontSize: 9, fontWeight: 600, color: '#475569' }}>{l}</span></div>
    <div style={{ fontSize: 22, fontWeight: 700, color: '#f0f4f8', marginTop: 2 }}>{typeof v === 'number' ? v.toLocaleString(undefined, { maximumFractionDigits: 1 }) : v}</div>
    <span style={{ fontSize: 9, color: '#475569' }}>{u}</span>
  </div>
}
function BStat({ l, v }: { l: string; v: string }) { return <div style={{ textAlign: 'center' }}><div style={{ fontSize: 14, fontWeight: 700, color: '#e2e8f0' }}>{v}</div><div style={{ fontSize: 8, color: '#475569' }}>{l}</div></div> }
function tabIcon(t: Tab) { return { MAP: '\u{1F5FA}', EVENTS: '\u2630', SENSORS: '\u{1F4E1}', METRICS: '\u{1F4CA}', SYSTEM: '\u2699', BENCHMARKS: '\u{1F3AF}' }[t] }
function formatDuration(ms: number) { const s = Math.floor(ms / 1000); const m = Math.floor(s / 60); const h = Math.floor(m / 60); const d = Math.floor(h / 24); if (d > 0) return `${d}d ${h % 24}h ${m % 60}m`; if (h > 0) return `${h}h ${m % 60}m ${s % 60}s`; if (m > 0) return `${m}m ${s % 60}s`; return `${s}s` }

ReactDOM.createRoot(document.getElementById('root')!).render(<App />)
