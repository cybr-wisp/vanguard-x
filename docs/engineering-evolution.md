# Engineering Evolution

This document records implementation and validation changes that affected the system.
It is not intended to reconstruct commit history.

## Estimator covariance update

The EKF documentation and implementation were aligned around the Joseph stabilized covariance update:

```text
P = (I - KH) P (I - KH)^T + K R K^T
```

The covariance matrix is subsequently symmetrized. Earlier documentation describing only the simplified covariance update was removed.

## Packet-loss benchmark interpretation

Several packet-loss runs produced lower measured RMSE at non-zero loss rates.
Those measurements are retained, but the result is treated as a benchmark anomaly rather than evidence that packet loss improves estimation.
A controlled follow-up is required before drawing a causal conclusion.

## Executor selection

Tracking remains on a dedicated consumer execution path.
Fixed-worker and virtual-thread alternatives remain an open experiment because a controlled concurrent benchmark has not established a measurable improvement.

## Estimator selection

The live tracking path currently constructs an Extended Kalman Filter.
An Interacting Multiple Model estimator is implemented and evaluated separately, but it has not replaced the EKF in the frozen full-system benchmark.

## Performance baselines

Historical benchmark results are retained rather than overwritten when later runs differ.
New performance work should either use the same workload and environment or be identified as a new benchmark configuration.
