import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import ReactDOM from 'react-dom/client'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { useTrackStream } from './hooks/useTrackStream'
import { useMetricsStream } from './hooks/useMetricsStream'
import type { FusedTrack, TrackEvent, SystemMetrics } from './lib/types'

const CENTER: [number, number] = [-117.15, 34.74]
const ZOOM = 11.5

const SENSORS = [
  { id: 'SSA-01', name: 'Sensor site Alpha', lng: -117.35, lat: 34.79 },
  { id: 'SSB-02', name: 'Sensor site Bravo', lng: -117.38, lat: 34.71 },
  { id: 'SSC-03', name: 'Sensor site Charlie', lng: -117.05, lat: 34.68 },
]

const ZONE_CENTER: [number, number] = [-117.05, 34.755]
const ZONE_RADIUS_LNG = 0.06
const ZONE_RADIUS_LAT = 0.04

function makeZonePolygon(): [number, number][] {
  const pts: [number, number][] = []
  for (let i = 0; i <= 64; i++) {
    const a = (i / 64) * Math.PI * 2
    pts.push([ZONE_CENTER[0] + Math.cos(a) * ZONE_RADIUS_LNG, ZONE_CENTER[1] + Math.sin(a) * ZONE_RADIUS_LAT])
  }
  return pts
}

const SC: Record<string, string> = { TENTATIVE: '#9ca3af', CONFIRMED: '#22c55e', COASTING: '#f59e0b', DROPPED: '#ef4444' }

/** Maintain per-track trail history from the WebSocket stream. */
function useTrailHistory(tracks: Map<string, FusedTrack>) {
  const trails = useRef<Map<string, [number, number][]>>(new Map())

  useEffect(() => {
    tracks.forEach((t, id) => {
      if (t.state === 'DROPPED') {
        trails.current.delete(id)
        return
      }
      // Only record trail points with valid coordinates
      if (Math.abs(t.px) <= 180 && Math.abs(t.py) <= 90) {
        const trail = trails.current.get(id) || []
        trail.push([t.px, t.py])
        if (trail.length > 120) trail.splice(0, trail.length - 120)
        trails.current.set(id, trail)
      }
    })
  }, [tracks])

  return trails
}

function App() {
  const mapContainer = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const [sel, setSel] = useState<string | null>(null)
  const [layers, setLayers] = useState({ fused: true, ellipse: true, geo: true, trails: true })
  const [, forceRender] = useState(0)

  // --- Live data from backend ---
  const { tracks, events, connected: trackWsConnected } = useTrackStream()
  const { metrics, connected: metricsWsConnected } = useMetricsStream()
  const trails = useTrailHistory(tracks)

  // Force re-render on each track update for SVG overlay
  useEffect(() => { forceRender(r => r + 1) }, [tracks])

  // Init map
  useEffect(() => {
    if (!mapContainer.current || mapRef.current) return
    const map = new maplibregl.Map({
      container: mapContainer.current,
      style: {
        version: 8,
        sources: { carto: { type: 'raster', tiles: ['https://a.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}@2x.png'], tileSize: 256, attribution: '&copy; CARTO' } },
        layers: [{ id: 'carto', type: 'raster', source: 'carto' }],
      },
      center: CENTER, zoom: ZOOM, attributionControl: false,
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    map.addControl(new maplibregl.ScaleControl({ maxWidth: 120 }), 'bottom-left')
    mapRef.current = map
    return () => map.remove()
  }, [])

  // Zone source
  useEffect(() => {
    const map = mapRef.current
    if (!map || !map.isStyleLoaded()) return
    if (!map.getSource('zone')) {
      map.addSource('zone', { type: 'geojson', data: { type: 'Feature', geometry: { type: 'Polygon', coordinates: [makeZonePolygon()] }, properties: {} } })
      map.addLayer({ id: 'zone-fill', type: 'fill', source: 'zone', paint: { 'fill-color': '#ef4444', 'fill-opacity': 0.06 } })
      map.addLayer({ id: 'zone-line', type: 'line', source: 'zone', paint: { 'line-color': '#ef4444', 'line-width': 2, 'line-dasharray': [4, 3] } })
    }
  })

  const aliveTracks = useMemo(() => {
    const result: [string, FusedTrack][] = []
    tracks.forEach((t, id) => {
      if (t.state !== 'DROPPED' &&
          Math.abs(t.px) <= 180 && Math.abs(t.py) <= 90 &&
          Number.isFinite(t.px) && Number.isFinite(t.py)) {
        result.push([id, t])
      }
    })
    return result
  }, [tracks])

  const selTrack = sel ? tracks.get(sel) : null
  const anyInZone = events.some(e => e.type === 'ZONE_ENTRY')

  const formatTime = (ms: number) => {
    const d = new Date(ms)
    return `${d.getUTCHours().toString().padStart(2, '0')}:${d.getUTCMinutes().toString().padStart(2, '0')}:${d.getUTCSeconds().toString().padStart(2, '0')}`
  }

  const nowUtc = useMemo(() => {
    const d = new Date()
    return `${d.getUTCHours().toString().padStart(2, '0')}:${d.getUTCMinutes().toString().padStart(2, '0')}:${d.getUTCSeconds().toString().padStart(2, '0')} UTC`
  }, [tracks]) // updates with each track batch

  const wsStatus = trackWsConnected && metricsWsConnected

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#0b1120', color: '#cbd5e1', fontFamily: '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif', fontSize: 13, overflow: 'hidden' }}>
      {/* Header */}
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 16px', background: '#0f172a', borderBottom: '1px solid #1e293b', flexShrink: 0, zIndex: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <svg width="30" height="30" viewBox="0 0 30 30"><path d="M15 3L26 26H4Z" fill="none" stroke="#3b82f6" strokeWidth="2" /><path d="M15 10L20 22H10Z" fill="#3b82f6" opacity=".3" /><circle cx="15" cy="17" r="2" fill="#3b82f6" /></svg>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#f1f5f9', letterSpacing: 0.5 }}>VANGUARD v1.0</span>
          <span style={{ fontSize: 12, color: '#64748b', fontStyle: 'italic' }}>Live Multi-Sensor Tracking</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 11, color: '#64748b' }}>SYSTEM TIME</span>
            <span style={{ fontFamily: 'monospace', fontWeight: 600, color: '#e2e8f0', fontSize: 14 }}>{nowUtc}</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <div style={{ width: 8, height: 8, borderRadius: '50%', background: wsStatus ? '#22c55e' : '#ef4444' }} />
            <span style={{ fontSize: 11, color: wsStatus ? '#22c55e' : '#ef4444' }}>{wsStatus ? 'CONNECTED' : 'DISCONNECTED'}</span>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 9, color: '#64748b' }}>OPERATOR</div>
            <div style={{ fontSize: 12, color: '#e2e8f0' }}>analyst</div>
          </div>
        </div>
      </header>

      <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
        {/* Left nav */}
        <nav style={{ width: 56, background: '#0f172a', borderRight: '1px solid #1e293b', display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 16, gap: 6, flexShrink: 0, zIndex: 20 }}>
          {[['MAP', '\u{1F5FA}\u{FE0F}'], ['TRACKS', '\u25CE'], ['EVENTS', '\u26A0\u{FE0F}'], ['SENSORS', '\u{1F4E1}'], ['METRICS', '\u{1F4CA}'], ['SYSTEM', '\u2699\u{FE0F}']].map(([l, ic]) => (
            <div key={l} style={{ width: 44, padding: '8px 0', display: 'flex', flexDirection: 'column', alignItems: 'center', fontSize: 9, color: '#64748b', cursor: 'pointer', borderRadius: 6 }}>
              <span style={{ fontSize: 16, marginBottom: 2 }}>{ic}</span>{l}
            </div>
          ))}
        </nav>

        {/* Map */}
        <div style={{ flex: 1, position: 'relative' }}>
          <div ref={mapContainer} style={{ width: '100%', height: '100%' }} />

          {/* Layer panel */}
          <div style={{ position: 'absolute', top: 12, left: 12, background: 'rgba(15,23,42,0.92)', border: '1px solid #1e293b', borderRadius: 8, padding: '12px 14px', zIndex: 10, width: 210, backdropFilter: 'blur(8px)' }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: '#e2e8f0', marginBottom: 10, letterSpacing: 0.5 }}>LAYERS</div>
            {([['FUSED TRACKS', 'fused', '#22c55e'], ['COVARIANCE ELLIPSE', 'ellipse', '#38bdf8'], ['GEOFENCES', 'geo', '#fbbf24'], ['TRAILS', 'trails', '#9ca3af']] as const).map(([label, key, color]) => (
              <div key={key} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 0' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><div style={{ width: 8, height: 8, borderRadius: '50%', background: color }} /><span style={{ fontSize: 12, color: '#e2e8f0', fontWeight: 500 }}>{label}</span></div>
                <ToggleSwitch checked={(layers as any)[key]} onChange={(v: boolean) => setLayers(l => ({ ...l, [key]: v }))} />
              </div>
            ))}
          </div>

          {/* Connection banner when disconnected */}
          {!wsStatus && (
            <div style={{ position: 'absolute', top: 12, left: '50%', transform: 'translateX(-50%)', background: 'rgba(127,29,29,0.92)', border: '1px solid #ef4444', borderRadius: 8, padding: '8px 20px', zIndex: 15, backdropFilter: 'blur(8px)' }}>
              <span style={{ color: '#fca5a5', fontSize: 13, fontWeight: 600 }}>Waiting for backend connection...</span>
            </div>
          )}

          {/* SVG overlay for tracks */}
          <svg style={{ position: 'absolute', inset: 0, pointerEvents: 'none', zIndex: 5 }} viewBox={`0 0 ${mapContainer.current?.clientWidth || 800} ${mapContainer.current?.clientHeight || 600}`}>
            {mapRef.current && aliveTracks.map(([id, t]) => {
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
                {/* Trail */}
                {layers.trails && trail.length > 2 && <polyline points={trail.map(p => { const pp = map.project(p as [number, number]); return `${pp.x},${pp.y}` }).join(' ')} fill="none" stroke={col} strokeWidth={1.5} opacity={0.35} />}
                {/* Ellipse */}
                {layers.ellipse && <ellipse cx={pt.x} cy={pt.y}
                  rx={(t.ellipseMajor || t.uncertainty * 0.6) * 0.6}
                  ry={(t.ellipseMinor || t.uncertainty * 0.4) * 0.6}
                  transform={`rotate(${t.ellipseAngle || hdgDeg - 90} ${pt.x} ${pt.y})`}
                  fill="none" stroke="#38bdf8" strokeWidth={1} opacity={0.3} strokeDasharray="5 3" />}
                {/* Arrow */}
                {layers.fused && <><line x1={pt.x} y1={pt.y} x2={ax} y2={ay} stroke={col} strokeWidth={2.5} opacity={0.8} /><polygon points={`${ax},${ay} ${ax - 9 * Math.cos(hdgRad - 0.4)},${ay - 9 * Math.sin(hdgRad - 0.4)} ${ax - 9 * Math.cos(hdgRad + 0.4)},${ay - 9 * Math.sin(hdgRad + 0.4)}`} fill={col} opacity={0.8} /></>}
                {/* Dot */}
                {layers.fused && <circle cx={pt.x} cy={pt.y} r={isSel ? 7 : 5} fill={col} stroke={isSel ? '#fff' : '#0f172a'} strokeWidth={isSel ? 2.5 : 1.5} />}
                {/* Label */}
                {layers.fused && <g transform={`translate(${pt.x + 16},${pt.y - 20})`}><rect x={0} y={-12} width={105} height={30} rx={4} fill={isSel ? '#14532d' : 'rgba(15,23,42,0.9)'} stroke={col} strokeWidth={isSel ? 1.5 : 0.5} /><text x={8} y={2} fill={col} fontSize={12} fontWeight={700} fontFamily="sans-serif">{id}</text><text x={8} y={14} fill="#94a3b8" fontSize={9} fontFamily="sans-serif">{Math.round(hdgDeg < 0 ? hdgDeg + 360 : hdgDeg)}&deg; / {Math.round(spd)} kn</text></g>}
              </g>
            })}

            {/* Zone label */}
            {layers.geo && mapRef.current && (() => { const zp = mapRef.current!.project(ZONE_CENTER); return <g><text x={zp.x} y={zp.y - 55} fill="#ef4444" fontSize={12} textAnchor="middle" fontWeight={600} fontFamily="sans-serif">RESTRICTED ZONE R-21</text>{anyInZone && <><text x={zp.x} y={zp.y - 10} fill="#ef4444" fontSize={16} textAnchor="middle" fontWeight={700} fontFamily="sans-serif">WARNING</text><text x={zp.x} y={zp.y + 8} fill="#ef444488" fontSize={11} textAnchor="middle" fontFamily="sans-serif">(Entering)</text></>}</g> })()}

            {/* Sensor labels */}
            {mapRef.current && SENSORS.map(s => { const sp = mapRef.current!.project([s.lng, s.lat]); return <g key={s.id}><circle cx={sp.x} cy={sp.y} r={8} fill="#1e1b4b" stroke="#a78bfa" strokeWidth={1.5} /><path d={`M${sp.x - 3},${sp.y - 1}Q${sp.x},${sp.y - 5},${sp.x + 3},${sp.y - 1}`} fill="none" stroke="#a78bfa" strokeWidth={1} /><path d={`M${sp.x - 5},${sp.y}Q${sp.x},${sp.y - 9},${sp.x + 5},${sp.y}`} fill="none" stroke="#a78bfa" strokeWidth={0.7} opacity={0.5} /><text x={sp.x + 14} y={sp.y - 4} fill="#e2e8f0" fontSize={12} fontWeight={600} fontFamily="sans-serif">{s.name.toUpperCase()}</text><text x={sp.x + 14} y={sp.y + 10} fill="#64748b" fontSize={10} fontFamily="sans-serif">ID: {s.id}</text></g> })}
          </svg>
        </div>

        {/* Right panel */}
        <aside style={{ width: 310, background: '#0f172a', borderLeft: '1px solid #1e293b', overflowY: 'auto', flexShrink: 0, zIndex: 20 }}>
          {selTrack ? <TrackInspector id={sel!} track={selTrack} events={events} onClose={() => setSel(null)} /> : <DefaultPanel events={events} />}
        </aside>
      </div>

      {/* Bottom metrics */}
      <footer style={{ display: 'flex', background: '#0f172a', borderTop: '1px solid #1e293b', flexShrink: 0, zIndex: 20, overflowX: 'auto' }}>
        <div style={{ padding: '6px 16px', borderRight: '1px solid #1e293b', display: 'flex', justifyContent: 'space-between', alignItems: 'center', minWidth: 60 }}>
          <span style={{ fontSize: 11, fontWeight: 600, color: '#94a3b8' }}>OPERATIONAL METRICS</span>
        </div>
        <MCard l="THROUGHPUT" s="Reports / s" v={metrics?.throughputReportsPerSec ?? 0} c="#3b82f6" />
        <div style={{ display: 'flex', flexDirection: 'column', padding: '6px 16px', borderRight: '1px solid #1e293b', minWidth: 180 }}>
          <span style={{ fontSize: 10, fontWeight: 600, color: '#64748b' }}>LATENCY <span style={{ fontWeight: 400, fontSize: 9 }}>(end-to-end)</span></span>
          <div style={{ display: 'flex', gap: 14, marginTop: 4 }}>
            <div><span style={{ fontSize: 9, color: '#64748b' }}>P50</span><div style={{ fontSize: 15, fontWeight: 700, color: '#e2e8f0' }}>{(metrics?.p50LatencyMs ?? 0).toFixed(0)} <span style={{ fontSize: 9, fontWeight: 400 }}>ms</span></div></div>
            <div><span style={{ fontSize: 9, color: '#64748b' }}>P95</span><div style={{ fontSize: 15, fontWeight: 700, color: '#e2e8f0' }}>{(metrics?.p95LatencyMs ?? 0).toFixed(0)} <span style={{ fontSize: 9, fontWeight: 400 }}>ms</span></div></div>
            <div><span style={{ fontSize: 9, color: '#64748b' }}>P99</span><div style={{ fontSize: 15, fontWeight: 700, color: '#f59e0b' }}>{(metrics?.p99LatencyMs ?? 0).toFixed(0)} <span style={{ fontSize: 9, fontWeight: 400 }}>ms</span></div></div>
          </div>
        </div>
        <MCard l="QUEUE DEPTH" s="Tracker ingest" v={metrics?.queueDepth ?? 0} c="#f59e0b" />
        <MCard l="KAFKA LAG" v={metrics?.kafkaLag ?? 0} c="#a78bfa" />
        <MCard l="ACTIVE TRACKS" v={metrics?.activeTracks ?? aliveTracks.length} c="#22c55e" />
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '4px 16px', borderRight: '1px solid #1e293b', minWidth: 120 }}>
          <span style={{ fontSize: 10, color: '#64748b', fontWeight: 600 }}>PIPELINE HEALTH</span>
          <span style={{ fontSize: 16, fontWeight: 700, color: wsStatus ? '#22c55e' : '#ef4444' }}>{wsStatus ? 'HEALTHY' : 'OFFLINE'}</span>
          <span style={{ fontSize: 9, color: '#475569' }}>{wsStatus ? 'All systems nominal' : 'No backend connection'}</span>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', padding: '6px 16px', minWidth: 180, fontSize: 11 }}>
          <span style={{ fontSize: 10, color: '#64748b', fontWeight: 600, marginBottom: 4 }}>SYSTEM SERVICES</span>
          {[
            ['Ingest Service', wsStatus],
            ['Tracker Service', wsStatus],
            ['Event Engine', wsStatus],
            ['WebSocket Gateway', trackWsConnected],
            ['UI / API', true],
          ].map(([s, ok]) => (
            <div key={s as string} style={{ display: 'flex', justifyContent: 'space-between', padding: '1px 0' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}><div style={{ width: 5, height: 5, borderRadius: '50%', background: ok ? '#22c55e' : '#ef4444' }} /><span style={{ color: '#94a3b8' }}>{s}</span></div>
              <span style={{ color: ok ? '#22c55e' : '#ef4444', fontSize: 10 }}>{ok ? 'Healthy' : 'Down'}</span>
            </div>
          ))}
        </div>
      </footer>
    </div>
  )
}

// --- Subcomponents ---

function ToggleSwitch({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return <div onClick={() => onChange(!checked)} style={{ width: 38, height: 20, borderRadius: 10, background: checked ? '#3b82f6' : '#334155', cursor: 'pointer', position: 'relative', transition: 'background 0.2s' }}><div style={{ width: 16, height: 16, borderRadius: 8, background: '#fff', position: 'absolute', top: 2, left: checked ? 20 : 2, transition: 'left 0.2s' }} /></div>
}

function TrackInspector({ id, track: t, events, onClose }: { id: string; track: FusedTrack; events: TrackEvent[]; onClose: () => void }) {
  const hdg = Math.atan2(t.vx, t.vy) * 57.3
  const hdgNorm = hdg < 0 ? hdg + 360 : hdg
  const spd = Math.sqrt(t.vx ** 2 + t.vy ** 2) * 1.944
  const sensorCount = t.contributingSensors?.length ?? 0

  return <>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontSize: 15, fontWeight: 700, color: '#f1f5f9' }}>TRACK INSPECTOR</span>
        <span style={{ cursor: 'pointer', color: '#64748b', fontSize: 14 }} onClick={onClose}>&#10005;</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <div style={{ width: 8, height: 8, borderRadius: '50%', background: SC[t.state] }} />
        <span style={{ fontSize: 17, fontWeight: 700, color: '#f1f5f9' }}>{id}</span>
        <span style={{ padding: '2px 10px', borderRadius: 4, background: t.state === 'CONFIRMED' ? '#14532d' : '#78350f', color: SC[t.state], fontSize: 10, fontWeight: 600, marginLeft: 'auto' }}>{t.state}</span>
      </div>
      <IR l="Lifecycle state" v={t.state} />
      <IR l="Last update" v={new Date(t.lastUpdateMs).toISOString().slice(11, 19) + ' UTC'} />
    </div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}>
      <Sec>Estimated state (WGS84)</Sec>
      <IR l="Position" v={`${t.py.toFixed(5)}\u00B0 N, ${Math.abs(t.px).toFixed(5)}\u00B0 W`} />
      <IR l="Velocity" v={`${spd.toFixed(1)} kn`} />
      <IR l="Heading" v={`${Math.round(hdgNorm)}\u00B0`} />
    </div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div><div style={{ fontSize: 10, color: '#64748b', marginBottom: 2 }}>CONFIDENCE</div><div style={{ fontSize: 22, fontWeight: 700, color: '#e2e8f0' }}>{Math.max(0.1, 1 - t.uncertainty / 200).toFixed(2)}</div></div>
        <div style={{ textAlign: 'right' }}><div style={{ fontSize: 10, color: '#64748b', marginBottom: 2 }}>TRACK QUALITY</div><div style={{ fontSize: 15, fontWeight: 700, color: t.uncertainty < 50 ? '#22c55e' : t.uncertainty < 100 ? '#f59e0b' : '#ef4444' }}>{t.uncertainty < 50 ? 'GOOD' : t.uncertainty < 100 ? 'FAIR' : 'POOR'}</div></div>
      </div>
    </div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}>
      <Sec>Contributing sensors ({sensorCount})</Sec>
      {SENSORS.map(s => {
        const active = t.contributingSensors?.includes(s.id)
        return <div key={s.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 14 }}>{'\u{1F4E1}'}</span>
            <span style={{ fontSize: 12, color: active ? '#e2e8f0' : '#475569' }}>{s.id} ({s.name.split(' ').pop()})</span>
          </div>
          {active && <span style={{ color: '#22c55e', fontSize: 10 }}>ACTIVE</span>}
        </div>
      })}
    </div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}>
      <Sec>Covariance (1-sigma ellipse)</Sec>
      <IR l="Major axis" v={`${(t.ellipseMajor ?? t.uncertainty * 12).toFixed(0)} m`} />
      <IR l="Minor axis" v={`${(t.ellipseMinor ?? t.uncertainty * 8).toFixed(0)} m`} />
      <IR l="Orientation" v={`${Math.round(t.ellipseAngle ?? hdgNorm)}\u00B0`} />
    </div>
    <div style={{ padding: '14px 16px' }}>
      <Sec>Recent events</Sec>
      {events.filter(e => e.trackId === id).slice(-6).map((e, i) => (
        <div key={i} style={{ display: 'flex', gap: 8, padding: '3px 0', fontSize: 11 }}>
          <div style={{ width: 6, height: 6, borderRadius: '50%', background: e.type === 'ZONE_ENTRY' ? '#ef4444' : e.type === 'ZONE_APPROACH' ? '#f59e0b' : '#22c55e', marginTop: 5, flexShrink: 0 }} />
          <div>
            <span style={{ color: '#64748b', marginRight: 6 }}>{new Date(e.timestampMs).toISOString().slice(11, 19)}</span>
            <span style={{ color: '#cbd5e1' }}>{e.type.replace(/_/g, ' ')} - {e.zoneId}</span>
          </div>
        </div>
      ))}
      {events.filter(e => e.trackId === id).length === 0 && <span style={{ color: '#475569', fontSize: 11 }}>No events for this track</span>}
    </div>
  </>
}

function DefaultPanel({ events }: { events: TrackEvent[] }) {
  return <div style={{ padding: 16 }}>
    <div style={{ fontSize: 15, fontWeight: 700, color: '#94a3b8', marginBottom: 8 }}>TRACK INSPECTOR</div>
    <p style={{ color: '#475569', fontSize: 12, lineHeight: 1.6 }}>Click any track on the map to inspect its state, velocity, uncertainty, and contributing sensors.</p>
    <div style={{ marginTop: 20 }}><Sec>Zone events</Sec>
      {events.slice(-10).map((e, i) => <div key={i} style={{ display: 'flex', gap: 8, padding: '3px 0', fontSize: 11 }}><div style={{ width: 6, height: 6, borderRadius: '50%', background: e.type === 'ZONE_ENTRY' ? '#ef4444' : e.type === 'ZONE_APPROACH' ? '#f59e0b' : '#22c55e', marginTop: 5, flexShrink: 0 }} /><div><span style={{ color: e.type === 'ZONE_ENTRY' ? '#fca5a5' : '#fcd34d' }}>{e.trackId}: </span><span style={{ color: '#94a3b8' }}>{e.type.replace(/_/g, ' ')} - {e.zoneId}</span></div></div>)}
      {events.length === 0 && <span style={{ color: '#475569', fontSize: 11 }}>No events yet</span>}
    </div>
  </div>
}

function IR({ l, v }: { l: string; v: string }) { return <div style={{ display: 'flex', justifyContent: 'space-between', padding: '3px 0', fontSize: 12 }}><span style={{ color: '#64748b' }}>{l}</span><span style={{ color: '#e2e8f0', fontFamily: 'monospace', fontSize: 12 }}>{v}</span></div> }
function Sec({ children }: { children: string }) { return <div style={{ fontSize: 11, fontWeight: 600, color: '#94a3b8', letterSpacing: 0.5, textTransform: 'uppercase', marginBottom: 8 }}>{children}</div> }
function MCard({ l, s, v, u, c }: { l: string; s?: string; v: number; u?: string; c?: string }) {
  return <div style={{ display: 'flex', flexDirection: 'column', minWidth: 110, padding: '6px 16px', borderRight: '1px solid #1e293b' }}>
    <span style={{ fontSize: 10, fontWeight: 600, color: '#64748b' }}>{l}</span>{s && <span style={{ fontSize: 9, color: '#475569' }}>{s}</span>}
    <div style={{ fontSize: 22, fontWeight: 700, color: c || '#e2e8f0', marginTop: 2 }}>{typeof v === 'number' ? v.toLocaleString(undefined, { maximumFractionDigits: 2 }) : v}<span style={{ fontSize: 10, fontWeight: 400, color: '#64748b', marginLeft: 2 }}>{u}</span></div>
  </div>
}

const btnStyle: React.CSSProperties = { background: '#1e293b', border: '1px solid #334155', borderRadius: 6, color: '#cbd5e1', padding: '5px 16px', cursor: 'pointer', fontSize: 12, fontFamily: 'inherit' }

ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>)
