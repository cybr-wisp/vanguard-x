import type { TrackEvent } from '../../lib/types'
import { MiniEvent } from '../ui/UiPrimitives'

export function RecentEvents({ events }: { events: TrackEvent[] }) {
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
