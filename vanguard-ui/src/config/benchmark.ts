export const BENCHMARK = {
  jvm: '21.0.12.1',
  cores: 8,

  positionRmse: 10.86,
  velocityRmse: 4.17,
  association: 100.0,
  falseTracks: 0,

  rawRmse: 29.24,
  fusedRmse: 10.82,
  fusionGain: 63.0,

  throughput: {
    50: 48_858,
    200: 21_348,
    500: 14_962,
    1000: 16_696,
  },

  operationalLatency: {
    p50: 13.49,
    p95: 18.45,
    p99: 20.73,
  },
} as const
