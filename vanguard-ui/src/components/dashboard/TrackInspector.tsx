import { Crosshair, Plane, X } from 'lucide-react'
import type { FusedTrack, TrackEvent, ZoneDefinition } from '../../lib/types'
import { TRACK_COLORS } from '../../config/tactical'
import { InfoRow, MiniEvent, StatePill } from '../ui/UiPrimitives'
import { fmtAge, fmtTime, headingDeg } from '../../lib/uiUtils'

export function TrackInspector({
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
        <InfoRow label="Ground speed" value={`${speedMps.toFixed(1)} m/s · ${Math.round(speedKnots)} kt`} />
        <InfoRow label="Heading" value={`${Math.round(heading)}°`} />
        <InfoRow label="Coordinates" value={`${track.py.toFixed(5)}, ${track.px.toFixed(5)}`} />
        <InfoRow label="Position uncertainty" value={`${track.uncertainty.toFixed(1)} m`} />
        <InfoRow label="Ellipse major" value={track.ellipseMajor != null ? `${track.ellipseMajor.toFixed(1)} m` : '—'} />
        <InfoRow label="Ellipse minor" value={track.ellipseMinor != null ? `${track.ellipseMinor.toFixed(1)} m` : '—'} />
        <InfoRow label="Last update" value={`${fmtTime(track.lastUpdateMs)} · ${fmtAge(now - track.lastUpdateMs)}`} />
        <InfoRow label="Sensor sources" value={track.contributingSensors?.join(', ') || '—'} />
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
