import { BENCHMARK } from '../config/benchmark'
import {
  BenchmarkPanel,
  BigMetric,
  InfoRow,
  TabShell,
} from '../components/ui/UiPrimitives'

export function BenchmarksTab() {
  return (
    <TabShell title="Benchmark Results" subtitle={`Frozen three-run median Â· JVM ${BENCHMARK.jvm} Â· ${BENCHMARK.cores} cores Â· FullBenchmark`}>
      <div className="benchmark-hero-grid">
        <BigMetric label="Position RMSE" value={BENCHMARK.positionRmse.toFixed(2)} unit="m" good />
        <BigMetric label="Association" value={`${BENCHMARK.association}%`} unit="accuracy" good />
        <BigMetric label="Fusion gain" value={`${BENCHMARK.fusionGain}%`} unit="RMSE reduction" good />
        <BigMetric label="False tracks" value={String(BENCHMARK.falseTracks)} unit="benchmark metric" good />
      </div>

      <div className="benchmark-columns">
        <BenchmarkPanel title="Tracking accuracy">
          <InfoRow label="Position RMSE" value={`${BENCHMARK.positionRmse.toFixed(2)} m`} />
          <InfoRow label="Velocity RMSE" value={`${BENCHMARK.velocityRmse.toFixed(2)} m/s`} />
          <InfoRow label="Association accuracy" value={`${BENCHMARK.association.toFixed(1)}%`} />
          <InfoRow label="False tracks" value={String(BENCHMARK.falseTracks)} />
        </BenchmarkPanel>

        <BenchmarkPanel title="Fusion vs raw">
          <InfoRow label="Raw RMSE" value={`${BENCHMARK.rawRmse.toFixed(2)} m`} />
          <InfoRow label="Fused RMSE" value={`${BENCHMARK.fusedRmse.toFixed(2)} m`} />
          <InfoRow label="Improvement" value={`${BENCHMARK.fusionGain}%`} />
          <InfoRow label="Event deduplication" value="1000 â†’ 1" />
          <InfoRow label="Replay determinism" value="IDENTICAL Â· Î” 0.00e+00 m" />
        </BenchmarkPanel>

        <BenchmarkPanel title="Throughput Â· after spatial index">
          {Object.entries(BENCHMARK.throughput).map(([targets, rate]) => (
            <InfoRow key={targets} label={`${targets} targets`} value={`${rate.toLocaleString()} reports/s`} />
          ))}
        </BenchmarkPanel>

        <BenchmarkPanel title="Tracking latency Â· 200 targets">
          <InfoRow label="Indexed p50" value={`${BENCHMARK.operationalLatency.p50.toFixed(2)} ms`} />
          <InfoRow label="Indexed p95" value={`${BENCHMARK.operationalLatency.p95.toFixed(2)} ms`} />
          <InfoRow label="Indexed p99" value={`${BENCHMARK.operationalLatency.p99.toFixed(2)} ms`} />
        </BenchmarkPanel>

        <BenchmarkPanel title="Covariance behavior">
          <InfoRow label="Measurement updates" value="uncertainty decreases" />
          <InfoRow label="Coasting" value="uncertainty increases" />
          <InfoRow label="Reacquisition" value="uncertainty decreases" />
        </BenchmarkPanel>

        <BenchmarkPanel title="Loss robustness">
          <InfoRow label="0% loss" value="100% association" />
          <InfoRow label="5% loss" value="100% association" />
          <InfoRow label="10% loss" value="100% association" />
          <InfoRow label="20% loss" value="100% association" />
          <InfoRow label="Re-entry correctness" value="new ZONE_ENTRY emitted" />
        </BenchmarkPanel>
      </div>

      <div className="cli-note">
        <span>REPRODUCE</span>
        <code>mvn -pl benchmarks exec:java "-Dexec.mainClass=com.vanguard.benchmark.FullBenchmark"</code>
      </div>
    </TabShell>
  )
}
