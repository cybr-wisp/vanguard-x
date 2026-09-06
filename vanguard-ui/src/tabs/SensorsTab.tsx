import { Radio } from 'lucide-react'
import type { FusedTrack } from '../lib/types'
import { SENSORS } from '../config/tactical'
import { InfoRow, StatusPill, TabShell } from '../components/ui/UiPrimitives'

export function SensorsTab({ tracks, trackConnected }: { tracks: Array<[string, FusedTrack]>; trackConnected: boolean }) {
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
