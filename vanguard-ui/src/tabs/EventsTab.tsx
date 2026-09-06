import type { TrackEvent } from '../lib/types'
import { EmptyState, EventPill, TabShell } from '../components/ui/UiPrimitives'
import { fmtTime } from '../lib/uiUtils'

export function EventsTab({ events }: { events: TrackEvent[] }) {
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
                <td>{event.previousState} → {event.newState}</td>
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
