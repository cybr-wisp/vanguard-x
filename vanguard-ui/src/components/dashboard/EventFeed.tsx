import type { TrackEvent } from '../../lib/types'
import { EventPill } from '../ui/UiPrimitives'
import { fmtTime } from '../../lib/uiUtils'

export function EventFeed({ events }: { events: TrackEvent[] }) {
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
          <span>{event.zoneId} · {event.previousState} → {event.newState}</span>
        </div>
      ))}

      {recent.length === 0 && <div className="event-feed-empty">No spatial events received yet.</div>}
    </div>
  )
}
