import type { FusedTrack } from '../lib/types'
import { EmptyState, StatePill, TabShell } from '../components/ui/UiPrimitives'
import { fmtAge, headingDeg } from '../lib/uiUtils'

export function TracksTab({
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
                <td>{Math.round(headingDeg(track.vx, track.vy))}°</td>
                <td>{track.uncertainty.toFixed(1)} m</td>
                <td>{track.contributingSensors?.join(', ') || '—'}</td>
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
