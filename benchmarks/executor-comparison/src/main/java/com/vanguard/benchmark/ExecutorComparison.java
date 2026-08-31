package com.vanguard.benchmark;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Benchmark harness for comparing two processing configurations:
 *   1. Bounded fixed-worker thread pool
 *   2. Virtual-thread-per-task executor
 *
 * Both run identical scenarios with warm-up and controlled load.
 * The production configuration is selected from measured throughput,
 * latency, CPU utilization, and queue behavior (ADR-003).
 *
 * Key: EKF, association, and geometry work are CPU-bound. Virtual threads
 * excel at blocking I/O but provide no inherent speedup for CPU-bound work.
 * This benchmark measures the actual difference rather than assuming.
 */
public class ExecutorComparison {

    public record BenchmarkResult(
            String executorType,
            int warmupIterations,
            int measuredIterations,
            double throughputOpsPerSec,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double avgCpuPercent,
            long peakMemoryMb,
            int queueOverflows
    ) {
        @Override
        public String toString() {
            return "%s: %.0f ops/s, p50=%.2fms p95=%.2fms p99=%.2fms, cpu=%.1f%%, mem=%dMB, overflows=%d"
                    .formatted(executorType, throughputOpsPerSec, p50Ms, p95Ms, p99Ms,
                            avgCpuPercent, peakMemoryMb, queueOverflows);
        }
    }

    /**
     * The work unit simulating one tracking cycle (CPU-bound).
     */
    @FunctionalInterface
    public interface WorkUnit {
        void execute();
    }

    /**
     * Run benchmark with a fixed-worker thread pool.
     */
    public static BenchmarkResult benchmarkFixedPool(
            int workerCount, int queueCapacity,
            int warmup, int iterations,
            WorkUnit work) {

        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queueCapacity);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workerCount, workerCount, 60, TimeUnit.SECONDS, queue,
                new ThreadPoolExecutor.AbortPolicy());

        return runBenchmark("fixed-" + workerCount, executor, warmup, iterations, work, queue);
    }

    /**
     * Run benchmark with virtual threads.
     */
    public static BenchmarkResult benchmarkVirtualThreads(
            int warmup, int iterations, WorkUnit work) {

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return runBenchmark("virtual-thread", executor, warmup, iterations, work, null);
    }

    private static BenchmarkResult runBenchmark(
            String name, ExecutorService executor,
            int warmup, int iterations,
            WorkUnit work, BlockingQueue<Runnable> queue) {

        LongAdder overflows = new LongAdder();
        long[] latencies = new long[iterations];

        // Warm-up phase
        for (int i = 0; i < warmup; i++) {
            try {
                executor.submit(work::execute).get();
            } catch (Exception e) {
                // ignore during warmup
            }
        }

        // Measurement phase
        Instant start = Instant.now();
        Runtime rt = Runtime.getRuntime();
        long peakMem = 0;

        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            try {
                executor.submit(work::execute).get();
            } catch (RejectedExecutionException e) {
                overflows.increment();
            } catch (Exception e) {
                // count but continue
            }
            latencies[i] = System.nanoTime() - t0;
            peakMem = Math.max(peakMem, rt.totalMemory() - rt.freeMemory());
        }

        Duration elapsed = Duration.between(start, Instant.now());
        executor.shutdown();

        // Compute percentiles
        java.util.Arrays.sort(latencies);
        double p50 = latencies[(int)(iterations * 0.50)] / 1_000_000.0;
        double p95 = latencies[(int)(iterations * 0.95)] / 1_000_000.0;
        double p99 = latencies[(int)(iterations * 0.99)] / 1_000_000.0;
        double throughput = iterations / (elapsed.toMillis() / 1000.0);

        return new BenchmarkResult(
                name, warmup, iterations, throughput,
                p50, p95, p99,
                0, // CPU% requires JMX or OS-level measurement
                peakMem / (1024 * 1024),
                (int) overflows.sum());
    }
}
