import type { FusedTrack } from '../../lib/types'
import { BENCHMARK } from '../../config/benchmark'
import { SENSORS } from '../../config/tactical'
import { SummaryStat } from '../ui/UiPrimitives'

export function SensorStatus({ tracks, connected }: { tracks: Array<[string, FusedTrack]>; connected: boolean }) {
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
          <span className="rail-count">FROZEN</span>
        </div>

        <div className="snapshot-grid">
          <SummaryStat label="RMSE" value={`${BENCHMARK.positionRmse.toFixed(1)} m`} />
          <SummaryStat label="ASSOC." value={`${BENCHMARK.association}%`} />
          <SummaryStat label="200 TARGETS" value={`${(BENCHMARK.throughput[200] / 1000).toFixed(1)}K/s`} />
          <SummaryStat label="INDEXED P99" value={`${BENCHMARK.operationalLatency.p99.toFixed(1)} ms`} />
        </div>

        <div className="snapshot-note">Measured benchmark Â· not live telemetry</div>
      </div>
    </section>
  )
}
