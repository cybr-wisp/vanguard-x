import React, { useState, useEffect, useRef, useCallback } from 'react'
import ReactDOM from 'react-dom/client'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'

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

function g() { let u = 0, v = 0; while (!u) u = Math.random(); v = Math.random(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v) }

interface Track { lng: number; lat: number; vlng: number; vlat: number; hdg: number; spd: number; unc: number; hits: number; misses: number; state: string; sensors: Set<string>; trail: [number, number][]; rawPts: [number, number][]; firstTick: number; lastTick: number; inZone: boolean }
interface Evt { tick: number; type: string; track: string; detail: string; time: string }

function initTracks(): Record<string, Track> { return {} }

const TGTS = [
  { id: 'T-1001', lng: -117.38, lat: 34.82, vlng: 0.00035, vlat: -0.00008, turn: 0.02, turnStart: 150 },
  { id: 'T-1002', lng: -117.0, lat: 34.80, vlng: -0.00025, vlat: -0.00015, turn: -0.015, turnStart: 120 },
  { id: 'T-1003', lng: -117.22, lat: 34.82, vlng: 0.00015, vlat: -0.0002, turn: 0.01, turnStart: 180 },
  { id: 'T-1004', lng: -117.32, lat: 34.68, vlng: 0.00028, vlat: 0.00012, turn: -0.018, turnStart: 140 },
  { id: 'T-1005', lng: -117.08, lat: 34.66, vlng: -0.0001, vlat: 0.00025, turn: 0.012, turnStart: 100 },
]

const SC: Record<string, string> = { TENTATIVE: '#9ca3af', CONFIRMED: '#22c55e', COASTING: '#f59e0b', DROPPED: '#ef4444' }

function App() {
  const mapContainer = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const [tick, setTick] = useState(0)
  const [running, setRunning] = useState(true)
  const [loss, setLoss] = useState(0)
  const [sel, setSel] = useState<string | null>(null)
  const [layers, setLayers] = useState({ raw: true, fused: true, ellipse: true, geo: true, trails: true })
  const tgtState = useRef(TGTS.map(t => ({ ...t })))
  const tracks = useRef<Record<string, Track>>({})
  const events = useRef<Evt[]>([])
  const met = useRef({ accepted: 0, dropped: 0 })
  const thruH = useRef<number[]>([])
  const [, forceRender] = useState(0)

  // Init map
  useEffect(() => {
    if (!mapContainer.current || mapRef.current) return
    const map = new maplibregl.Map({
      container: mapContainer.current,
      style: { version: 8, sources: { carto: { type: 'raster', tiles: ['https://a.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}@2x.png'], tileSize: 256, attribution: '&copy; CARTO' } }, layers: [{ id: 'carto', type: 'raster', source: 'carto' }] },
      center: CENTER, zoom: ZOOM, attributionControl: false,
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right')
    map.addControl(new maplibregl.ScaleControl({ maxWidth: 120 }), 'bottom-left')
    mapRef.current = map
    return () => map.remove()
  }, [])

  // Simulation loop
  useEffect(() => { if (!running) return; const iv = setInterval(() => setTick(t => t + 1), 70); return () => clearInterval(iv) }, [running])

  useEffect(() => {
    if (tick === 0) return
    const map = mapRef.current; if (!map) return
    const tr = tracks.current, tgts = tgtState.current

    // Step targets
    tgts.forEach(t => {
      if (tick > t.turnStart) { const c = Math.cos(t.turn * 0.001), s = Math.sin(t.turn * 0.001); const nv = t.vlng * c - t.vlat * s, nl = t.vlng * s + t.vlat * c; t.vlng = nv; t.vlat = nl }
      t.lng += t.vlng; t.lat += t.vlat
      t.hdg = Math.atan2(t.vlng, t.vlat) * 57.3; if (t.hdg < 0) t.hdg += 360
      t.spd = Math.sqrt(t.vlng ** 2 + t.vlat ** 2) * 111000 * 1.944 // m/s to knots approx
    })

    // Observe + fuse
    tgts.forEach(tgt => {
      const observations: [number, number][] = []
      const sensorIds: string[] = []
      SENSORS.forEach(s => {
        if (Math.random() < loss) { met.current.dropped++; return }
        const nLng = tgt.lng + g() * 0.003, nLat = tgt.lat + g() * 0.002
        observations.push([nLng, nLat]); sensorIds.push(s.id); met.current.accepted++
      })

      const prev = tr[tgt.id]
      if (observations.length > 0) {
        const aLng = observations.reduce((s, o) => s + o[0], 0) / observations.length
        const aLat = observations.reduce((s, o) => s + o[1], 0) / observations.length
        if (!prev) {
          tr[tgt.id] = { lng: aLng, lat: aLat, vlng: 0, vlat: 0, hdg: tgt.hdg, spd: tgt.spd, unc: 65, hits: 1, misses: 0, state: 'TENTATIVE', sensors: new Set(sensorIds), trail: [[aLng, aLat]], rawPts: observations, firstTick: tick, lastTick: tick, inZone: false }
        } else {
          const k = Math.min(0.35, 1 / (1 + prev.unc * 0.008))
          const nLng = prev.lng + prev.vlng + k * (aLng - prev.lng - prev.vlng)
          const nLat = prev.lat + prev.vlat + k * (aLat - prev.lat - prev.vlat)
          const sn = new Set(prev.sensors); sensorIds.forEach(s => sn.add(s))
          let state = prev.state; if (prev.hits + 1 >= 3 && state === 'TENTATIVE') state = 'CONFIRMED'; if (prev.state === 'COASTING') state = 'CONFIRMED'
          tr[tgt.id] = { lng: nLng, lat: nLat, vlng: 0.9 * prev.vlng + 0.1 * (nLng - prev.lng), vlat: 0.9 * prev.vlat + 0.1 * (nLat - prev.lat), hdg: tgt.hdg, spd: tgt.spd, unc: prev.unc * (1 - k * 0.4), hits: prev.hits + 1, misses: 0, state, sensors: sn, trail: [...prev.trail.slice(-120), [nLng, nLat]], rawPts: [...prev.rawPts.slice(-40), ...observations], firstTick: prev.firstTick, lastTick: tick, inZone: false }
        }
      } else if (prev) {
        const m = prev.misses + 1; let state = prev.state; if (m >= 3 && state === 'CONFIRMED') state = 'COASTING'; if (m >= 12) state = 'DROPPED'
        tr[tgt.id] = { ...prev, lng: prev.lng + prev.vlng, lat: prev.lat + prev.vlat, unc: prev.unc * 1.06, misses: m, state, trail: [...prev.trail.slice(-120), [prev.lng + prev.vlng, prev.lat + prev.vlat]], lastTick: tick, inZone: false }
      }
    })

    // Zone check
    Object.entries(tr).forEach(([id, t]) => {
      if (t.state === 'DROPPED') return
      const dx = (t.lng - ZONE_CENTER[0]) / ZONE_RADIUS_LNG, dy = (t.lat - ZONE_CENTER[1]) / ZONE_RADIUS_LAT
      const inside = dx * dx + dy * dy <= 1
      const near = dx * dx + dy * dy <= 1.8
      if (inside && !t.inZone) events.current = [...events.current.slice(-20), { tick, type: 'BREACH', track: id, detail: 'Geofence breach: R-21', time: simTime(tick) }]
      else if (!inside && t.inZone) events.current = [...events.current.slice(-20), { tick, type: 'EXIT', track: id, detail: 'Exited R-21', time: simTime(tick) }]
      else if (near && !inside && !t.inZone) events.current = [...events.current.slice(-20), { tick, type: 'WARNING', track: id, detail: 'Proximity: R-21 (WARNING)', time: simTime(tick) }]
      t.inZone = inside
    })

    thruH.current = [...thruH.current.slice(-50), Math.round(met.current.accepted / (tick * 0.07))]
    forceRender(r => r + 1)
  }, [tick, loss])

  const simTime = (t: number) => { const s = Math.floor(t * 0.07); return `14:${(28 + Math.floor(s / 60)).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}` }
  const reset = useCallback(() => { setTick(0); tgtState.current = TGTS.map(t => ({ ...t })); tracks.current = {}; events.current = []; met.current = { accepted: 0, dropped: 0 }; thruH.current = []; setSel(null) }, [])

  const tr = tracks.current, ev = events.current, alive = Object.values(tr).filter(t => t.state !== 'DROPPED'), selT = sel ? tr[sel] : null
  const anyInZone = Object.values(tr).some(t => t.inZone)

  // Update map sources
  useEffect(() => {
    const map = mapRef.current; if (!map || !map.isStyleLoaded()) return

    // Zone
    if (!map.getSource('zone')) {
      map.addSource('zone', { type: 'geojson', data: { type: 'Feature', geometry: { type: 'Polygon', coordinates: [makeZonePolygon()] }, properties: {} } })
      map.addLayer({ id: 'zone-fill', type: 'fill', source: 'zone', paint: { 'fill-color': '#ef4444', 'fill-opacity': 0.06 } })
      map.addLayer({ id: 'zone-line', type: 'line', source: 'zone', paint: { 'line-color': '#ef4444', 'line-width': 2, 'line-dasharray': [4, 3] } })
    }
  }, [tick])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#0b1120', color: '#cbd5e1', fontFamily: '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif', fontSize: 13, overflow: 'hidden' }}>
      {/* Header */}
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 16px', background: '#0f172a', borderBottom: '1px solid #1e293b', flexShrink: 0, zIndex: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <svg width="30" height="30" viewBox="0 0 30 30"><path d="M15 3L26 26H4Z" fill="none" stroke="#3b82f6" strokeWidth="2" /><path d="M15 10L20 22H10Z" fill="#3b82f6" opacity=".3" /><circle cx="15" cy="17" r="2" fill="#3b82f6" /></svg>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#f1f5f9', letterSpacing: 0.5 }}>VANGUARD v1.0</span>
          <span style={{ fontSize: 12, color: '#64748b', fontStyle: 'italic' }}>Simulated Multi-Sensor Tracking Demo</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 11, color: '#64748b' }}>SIMULATION TIME</span>
            <span style={{ fontFamily: 'monospace', fontWeight: 600, color: '#e2e8f0', fontSize: 14 }}>{simTime(tick)} UTC</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ fontSize: 11, color: '#64748b' }}>TIME ACCELERATION</span>
            <select style={{ background: '#1e293b', border: '1px solid #334155', borderRadius: 4, color: '#e2e8f0', padding: '3px 8px', fontSize: 12 }}><option>10x</option><option>1x</option><option>5x</option></select>
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <button onClick={() => setRunning(!running)} style={btnStyle}>{running ? 'Pause' : 'Resume'}</button>
            <button onClick={reset} style={btnStyle}>Reset</button>
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
          {[['MAP', '🗺️'], ['TRACKS', '◎'], ['EVENTS', '⚠️'], ['SENSORS', '📡'], ['METRICS', '📊'], ['SYSTEM', '⚙️']].map(([l, ic]) => (
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
            {([['RAW OBSERVATIONS', 'raw', '#c084fc'], ['FUSED TRACKS', 'fused', '#22c55e'], ['COVARIANCE ELLIPSE', 'ellipse', '#38bdf8'], ['GEOFENCES', 'geo', '#fbbf24'], ['TRAILS', 'trails', '#9ca3af']] as const).map(([label, key, color]) => (
              <div key={key} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 0' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><div style={{ width: 8, height: 8, borderRadius: '50%', background: color }} /><span style={{ fontSize: 12, color: '#e2e8f0', fontWeight: 500 }}>{label}</span></div>
                <ToggleSwitch checked={(layers as any)[key]} onChange={(v: boolean) => setLayers(l => ({ ...l, [key]: v }))} />
              </div>
            ))}
          </div>

          {/* Packet loss control */}
          <div style={{ position: 'absolute', bottom: 40, left: 12, background: 'rgba(15,23,42,0.92)', border: '1px solid #1e293b', borderRadius: 8, padding: '8px 14px', zIndex: 10, width: 210, backdropFilter: 'blur(8px)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: '#94a3b8', marginBottom: 4 }}><span>Packet loss injection</span><span style={{ color: loss > 0 ? '#f87171' : '#64748b', fontWeight: 600 }}>{Math.round(loss * 100)}%</span></div>
            <input type="range" min={0} max={0.4} step={0.02} value={loss} onChange={e => setLoss(+e.target.value)} style={{ width: '100%', accentColor: '#f87171' }} />
          </div>

          {/* SVG overlay for tracks */}
          <svg style={{ position: 'absolute', inset: 0, pointerEvents: 'none', zIndex: 5 }} viewBox={`0 0 ${mapContainer.current?.clientWidth || 800} ${mapContainer.current?.clientHeight || 600}`}>
            {mapRef.current && Object.entries(tr).map(([id, t]) => {
              if (t.state === 'DROPPED') return null
              const map = mapRef.current!
              const pt = map.project([t.lng, t.lat])
              const col = SC[t.state]
              const isSel = sel === id
              const hdgRad = (t.hdg - 90) * Math.PI / 180
              const arrowLen = 30
              const ax = pt.x + arrowLen * Math.cos(hdgRad), ay = pt.y + arrowLen * Math.sin(hdgRad)

              return <g key={id} style={{ pointerEvents: 'all', cursor: 'pointer' }} onClick={() => setSel(id)}>
                {/* Raw scatter */}
                {layers.raw && t.rawPts.slice(-30).map((r, i) => { const rp = map.project(r as [number, number]); return <circle key={i} cx={rp.x} cy={rp.y} r={2} fill="#c084fc" opacity={0.3} /> })}
                {/* Trail */}
                {layers.trails && t.trail.length > 2 && <polyline points={t.trail.map(p => { const pp = map.project(p as [number, number]); return `${pp.x},${pp.y}` }).join(' ')} fill="none" stroke={col} strokeWidth={1.5} opacity={0.35} />}
                {/* Ellipse */}
                {layers.ellipse && <ellipse cx={pt.x} cy={pt.y} rx={t.unc * 0.6} ry={t.unc * 0.4} transform={`rotate(${t.hdg - 90} ${pt.x} ${pt.y})`} fill="none" stroke="#38bdf8" strokeWidth={1} opacity={0.3} strokeDasharray="5 3" />}
                {/* Arrow */}
                {layers.fused && <><line x1={pt.x} y1={pt.y} x2={ax} y2={ay} stroke={col} strokeWidth={2.5} opacity={0.8} /><polygon points={`${ax},${ay} ${ax - 9 * Math.cos(hdgRad - 0.4)},${ay - 9 * Math.sin(hdgRad - 0.4)} ${ax - 9 * Math.cos(hdgRad + 0.4)},${ay - 9 * Math.sin(hdgRad + 0.4)}`} fill={col} opacity={0.8} /></>}
                {/* Dot */}
                {layers.fused && <circle cx={pt.x} cy={pt.y} r={isSel ? 7 : 5} fill={col} stroke={isSel ? '#fff' : '#0f172a'} strokeWidth={isSel ? 2.5 : 1.5} />}
                {/* Label */}
                {layers.fused && <g transform={`translate(${pt.x + 16},${pt.y - 20})`}><rect x={0} y={-12} width={105} height={30} rx={4} fill={isSel ? '#14532d' : 'rgba(15,23,42,0.9)'} stroke={col} strokeWidth={isSel ? 1.5 : 0.5} /><text x={8} y={2} fill={col} fontSize={12} fontWeight={700} fontFamily="sans-serif">{id}</text><text x={8} y={14} fill="#94a3b8" fontSize={9} fontFamily="sans-serif">{Math.round(t.hdg)}&deg; / {Math.round(t.spd)} kn</text></g>}
              </g>
            })}

            {/* Zone label */}
            {layers.geo && mapRef.current && (() => { const zp = mapRef.current!.project(ZONE_CENTER); return <g><text x={zp.x} y={zp.y - 55} fill="#ef4444" fontSize={12} textAnchor="middle" fontWeight={600} fontFamily="sans-serif">RESTRICTED ZONE R-21</text>{anyInZone && <><text x={zp.x} y={zp.y - 10} fill="#ef4444" fontSize={16} textAnchor="middle" fontWeight={700} fontFamily="sans-serif">WARNING</text><text x={zp.x} y={zp.y + 8} fill="#ef444488" fontSize={11} textAnchor="middle" fontFamily="sans-serif">(Entering)</text></>}</g> })()}

            {/* Sensor labels */}
            {mapRef.current && SENSORS.map(s => { const sp = mapRef.current!.project([s.lng, s.lat]); return <g key={s.id}><circle cx={sp.x} cy={sp.y} r={8} fill="#1e1b4b" stroke="#a78bfa" strokeWidth={1.5} /><path d={`M${sp.x - 3},${sp.y - 1}Q${sp.x},${sp.y - 5},${sp.x + 3},${sp.y - 1}`} fill="none" stroke="#a78bfa" strokeWidth={1} /><path d={`M${sp.x - 5},${sp.y}Q${sp.x},${sp.y - 9},${sp.x + 5},${sp.y}`} fill="none" stroke="#a78bfa" strokeWidth={0.7} opacity={0.5} /><text x={sp.x + 14} y={sp.y - 4} fill="#e2e8f0" fontSize={12} fontWeight={600} fontFamily="sans-serif">{s.name.toUpperCase()}</text><text x={sp.x + 14} y={sp.y + 10} fill="#64748b" fontSize={10} fontFamily="sans-serif">ID: {s.id}</text></g> })}

            {/* Selected track legend */}
            {sel && selT && mapRef.current && (() => { const w = mapContainer.current?.clientWidth || 800, h = mapContainer.current?.clientHeight || 600; return <g transform={`translate(${70},${h - 180})`}><rect x={0} y={0} width={185} height={65} rx={6} fill="rgba(15,23,42,0.92)" stroke="#334155" strokeWidth={0.5} /><text x={14} y={18} fill="#e2e8f0" fontSize={12} fontWeight={600} fontFamily="sans-serif">SELECTED TRACK</text><circle cx={22} cy={34} r={3.5} fill="#c084fc" /><text x={32} y={38} fill="#94a3b8" fontSize={11} fontFamily="sans-serif">Raw observations (noisy)</text><text x={22} y={49} fill="#475569" fontSize={11} fontFamily="sans-serif">&#8595;</text><line x1={14} y1={56} x2={36} y2={56} stroke="#22c55e" strokeWidth={2.5} /><polygon points="36,56 32,53 32,59" fill="#22c55e" /><text x={42} y={60} fill="#94a3b8" fontSize={11} fontFamily="sans-serif">Fused estimate (clean)</text></g> })()}
          </svg>
        </div>

        {/* Right panel */}
        <aside style={{ width: 310, background: '#0f172a', borderLeft: '1px solid #1e293b', overflowY: 'auto', flexShrink: 0, zIndex: 20 }}>
          {selT ? <TrackInspector id={sel!} track={selT} tick={tick} simTime={simTime} events={ev} onClose={() => setSel(null)} /> : <DefaultPanel events={ev} />}
        </aside>
      </div>

      {/* Bottom metrics */}
      <footer style={{ display: 'flex', background: '#0f172a', borderTop: '1px solid #1e293b', flexShrink: 0, zIndex: 20, overflowX: 'auto' }}>
        <div style={{ padding: '6px 16px', borderRight: '1px solid #1e293b', display: 'flex', justifyContent: 'space-between', alignItems: 'center', minWidth: 60 }}>
          <span style={{ fontSize: 11, fontWeight: 600, color: '#94a3b8' }}>OPERATIONAL METRICS</span>
        </div>
        <MCard l="THROUGHPUT" s="Observations / s" v={thruH.current[thruH.current.length - 1] || 0} d={thruH.current} c="#3b82f6" t="+8.4%" />
        <div style={{ display: 'flex', flexDirection: 'column', padding: '6px 16px', borderRight: '1px solid #1e293b', minWidth: 180 }}>
          <span style={{ fontSize: 10, fontWeight: 600, color: '#64748b' }}>LATENCY <span style={{ fontWeight: 400, fontSize: 9 }}>(end-to-end)</span></span>
          <div style={{ display: 'flex', gap: 14, marginTop: 4 }}>
            <div><span style={{ fontSize: 9, color: '#64748b' }}>P50</span><div style={{ fontSize: 15, fontWeight: 700, color: '#e2e8f0' }}>152 <span style={{ fontSize: 9, fontWeight: 400 }}>ms</span></div></div>
            <div><span style={{ fontSize: 9, color: '#64748b' }}>P95</span><div style={{ fontSize: 15, fontWeight: 700, color: '#e2e8f0' }}>312 <span style={{ fontSize: 9, fontWeight: 400 }}>ms</span></div></div>
            <div><span style={{ fontSize: 9, color: '#64748b' }}>P99</span><div style={{ fontSize: 15, fontWeight: 700, color: '#f59e0b' }}>612 <span style={{ fontSize: 9, fontWeight: 400 }}>ms</span></div></div>
          </div>
        </div>
        <MCard l="QUEUE DEPTH" s="Tracker ingest" v={1247 + Math.round(loss * 3000)} c="#f59e0b" t={loss > 0 ? '+6.1%' : ''} />
        <MCard l="KAFKA CONSUMER LAG" v={2341 + Math.round(loss * 5000)} c="#a78bfa" t={loss > 0 ? '+9.3%' : ''} />
        <MCard l="PACKET LOSS" s="(5 min)" v={+(loss * 100).toFixed(2)} u="%" c={loss > 0 ? '#f87171' : '#22c55e'} />
        <MCard l="ACTIVE TRACKS" v={alive.length} c="#22c55e" t={`+${alive.length}`} />
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '4px 16px', borderRight: '1px solid #1e293b', minWidth: 120 }}>
          <span style={{ fontSize: 10, color: '#64748b', fontWeight: 600 }}>PROCESSOR HEALTH</span>
          <span style={{ fontSize: 16, fontWeight: 700, color: '#22c55e' }}>HEALTHY</span>
          <span style={{ fontSize: 9, color: '#475569' }}>All systems nominal</span>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', padding: '6px 16px', minWidth: 180, fontSize: 11 }}>
          <span style={{ fontSize: 10, color: '#64748b', fontWeight: 600, marginBottom: 4 }}>SYSTEM SERVICES</span>
          {['Ingest Service', 'Tracker Service', 'Event Engine', 'WebSocket Gateway', 'UI / API'].map(s => (
            <div key={s} style={{ display: 'flex', justifyContent: 'space-between', padding: '1px 0' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}><div style={{ width: 5, height: 5, borderRadius: '50%', background: '#22c55e' }} /><span style={{ color: '#94a3b8' }}>{s}</span></div>
              <span style={{ color: '#22c55e', fontSize: 10 }}>Healthy</span>
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

function TrackInspector({ id, track: t, tick, simTime, events, onClose }: { id: string; track: Track; tick: number; simTime: (t: number) => string; events: Evt[]; onClose: () => void }) {
  return <>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontSize: 15, fontWeight: 700, color: '#f1f5f9' }}>TRACK INSPECTOR</span>
        <div style={{ display: 'flex', gap: 8 }}><span style={{ cursor: 'pointer', color: '#64748b', fontSize: 14 }}>&#8211;</span><span style={{ cursor: 'pointer', color: '#64748b', fontSize: 14 }} onClick={onClose}>&#10005;</span></div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <div style={{ width: 8, height: 8, borderRadius: '50%', background: SC[t.state] }} />
        <span style={{ fontSize: 17, fontWeight: 700, color: '#f1f5f9' }}>{id}</span>
        <span style={{ padding: '2px 10px', borderRadius: 4, background: t.state === 'CONFIRMED' ? '#14532d' : '#78350f', color: SC[t.state], fontSize: 10, fontWeight: 600, marginLeft: 'auto' }}>{t.state}</span>
      </div>
      <IR l="Lifecycle state" v={t.state} /><IR l="First seen (Sim)" v={simTime(t.firstTick)} /><IR l="Last update (Sim)" v={simTime(t.lastTick)} /><IR l="Time since update" v={`${((tick - t.lastTick) * 0.07).toFixed(1)} s`} />
    </div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}><Sec>Estimated state (WGS84)</Sec><IR l="Position" v={`${t.lat.toFixed(5)}\u00B0 N, ${Math.abs(t.lng).toFixed(5)}\u00B0 W`} /><IR l="Velocity" v={`${t.spd.toFixed(1)} kn`} /><IR l="Heading" v={`${Math.round(t.hdg)}\u00B0`} /><IR l="Altitude" v="2,150 ft" /></div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}><div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}><div><div style={{ fontSize: 10, color: '#64748b', marginBottom: 2 }}>CONFIDENCE</div><div style={{ fontSize: 22, fontWeight: 700, color: '#e2e8f0' }}>{Math.max(0.1, 1 - t.unc / 200).toFixed(2)}</div></div><div style={{ textAlign: 'right' }}><div style={{ fontSize: 10, color: '#64748b', marginBottom: 2 }}>TRACK QUALITY</div><div style={{ fontSize: 15, fontWeight: 700, color: t.unc < 50 ? '#22c55e' : t.unc < 100 ? '#f59e0b' : '#ef4444' }}>{t.unc < 50 ? 'GOOD' : t.unc < 100 ? 'FAIR' : 'POOR'}</div></div></div></div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}><Sec>Contributing sensors ({t.sensors.size})</Sec>{SENSORS.map(s => { const a = t.sensors.has(s.id); const v = (0.5 + Math.random() * 0.3); return <div key={s.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 0' }}><div style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span style={{ fontSize: 14 }}>📡</span><span style={{ fontSize: 12, color: a ? '#e2e8f0' : '#475569' }}>{s.id} ({s.name.split(' ').pop()})</span></div>{a && <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}><div style={{ width: 50, height: 4, borderRadius: 2, background: '#1e293b', overflow: 'hidden' }}><div style={{ width: `${v * 100}%`, height: '100%', borderRadius: 2, background: '#3b82f6' }} /></div><span style={{ fontSize: 11, color: '#94a3b8', fontFamily: 'monospace' }}>{v.toFixed(2)}</span></div>}</div> })}</div>
    <div style={{ padding: '14px 16px', borderBottom: '1px solid #1e293b' }}><Sec>Covariance (1-sigma ellipse)</Sec><IR l="Major axis" v={`${(t.unc * 12).toFixed(0)} m`} /><IR l="Minor axis" v={`${(t.unc * 8).toFixed(0)} m`} /><IR l="Orientation" v={`${Math.round(Math.atan2(t.vlng, t.vlat) * 57.3)}\u00B0`} /><IR l="Area (1-sigma)" v={`${(t.unc * 12 * t.unc * 8 * Math.PI / 1e6).toFixed(2)} km\u00B2`} /></div>
    <div style={{ padding: '14px 16px' }}><Sec>Recent events</Sec>{events.filter(e => e.track === id).slice(-4).map((e, i) => <div key={i} style={{ display: 'flex', gap: 8, padding: '3px 0', fontSize: 11 }}><div style={{ width: 6, height: 6, borderRadius: '50%', background: e.type === 'BREACH' ? '#ef4444' : e.type === 'WARNING' ? '#f59e0b' : '#22c55e', marginTop: 5, flexShrink: 0 }} /><div><span style={{ color: '#64748b', marginRight: 6 }}>{e.time}</span><span style={{ color: '#cbd5e1' }}>{e.detail}</span></div></div>)}{events.filter(e => e.track === id).length === 0 && <span style={{ color: '#475569', fontSize: 11 }}>No events for this track</span>}</div>
  </>
}

function DefaultPanel({ events }: { events: Evt[] }) {
  return <div style={{ padding: 16 }}>
    <div style={{ fontSize: 15, fontWeight: 700, color: '#94a3b8', marginBottom: 8 }}>TRACK INSPECTOR</div>
    <p style={{ color: '#475569', fontSize: 12, lineHeight: 1.6 }}>Click any track on the map to inspect its state, velocity, uncertainty, and contributing sensors.</p>
    <div style={{ marginTop: 20 }}><Sec>Zone events</Sec>
      {events.slice(-10).map((e, i) => <div key={i} style={{ display: 'flex', gap: 8, padding: '3px 0', fontSize: 11 }}><div style={{ width: 6, height: 6, borderRadius: '50%', background: e.type === 'BREACH' ? '#ef4444' : e.type === 'WARNING' ? '#f59e0b' : '#22c55e', marginTop: 5, flexShrink: 0 }} /><div><span style={{ color: e.type === 'BREACH' ? '#fca5a5' : '#fcd34d' }}>{e.track}: </span><span style={{ color: '#94a3b8' }}>{e.detail}</span></div></div>)}
      {events.length === 0 && <span style={{ color: '#475569', fontSize: 11 }}>No events yet</span>}
    </div>
  </div>
}

function IR({ l, v }: { l: string; v: string }) { return <div style={{ display: 'flex', justifyContent: 'space-between', padding: '3px 0', fontSize: 12 }}><span style={{ color: '#64748b' }}>{l}</span><span style={{ color: '#e2e8f0', fontFamily: 'monospace', fontSize: 12 }}>{v}</span></div> }
function Sec({ children }: { children: string }) { return <div style={{ fontSize: 11, fontWeight: 600, color: '#94a3b8', letterSpacing: 0.5, textTransform: 'uppercase', marginBottom: 8 }}>{children}</div> }
function MCard({ l, s, v, u, c, d, t }: { l: string; s?: string; v: number; u?: string; c?: string; d?: number[]; t?: string }) {
  return <div style={{ display: 'flex', flexDirection: 'column', minWidth: 110, padding: '6px 16px', borderRight: '1px solid #1e293b' }}>
    <span style={{ fontSize: 10, fontWeight: 600, color: '#64748b' }}>{l}</span>{s && <span style={{ fontSize: 9, color: '#475569' }}>{s}</span>}
    <div style={{ fontSize: 22, fontWeight: 700, color: c || '#e2e8f0', marginTop: 2 }}>{typeof v === 'number' ? v.toLocaleString(undefined, { maximumFractionDigits: 2 }) : v}<span style={{ fontSize: 10, fontWeight: 400, color: '#64748b', marginLeft: 2 }}>{u}</span></div>
    {t && <span style={{ fontSize: 9, color: t.startsWith('+') ? '#22c55e' : t.startsWith('-') ? '#ef4444' : '#94a3b8' }}>{t}</span>}
    {d && d.length > 2 && <Sp d={d} c={c || '#3b82f6'} />}
  </div>
}
function Sp({ d, c }: { d: number[]; c: string }) { const mx = Math.max(...d, 1), pts = d.map((v, i) => `${(i / (d.length - 1)) * 100},${100 - ((v / mx) * 85)}`).join(' '); return <svg viewBox="0 0 100 100" preserveAspectRatio="none" style={{ width: '100%', height: 20, marginTop: 2 }}><polyline points={pts} fill="none" stroke={c} strokeWidth={2} vectorEffect="non-scaling-stroke" /><polyline points={`0,100 ${pts} 100,100`} fill={c} opacity={0.08} /></svg> }

const btnStyle: React.CSSProperties = { background: '#1e293b', border: '1px solid #334155', borderRadius: 6, color: '#cbd5e1', padding: '5px 16px', cursor: 'pointer', fontSize: 12, fontFamily: 'inherit' }

ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>)
