package com.vanguard.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class SequenceTrackerConcurrencyTest {

    @Test
    void concurrentSameSequenceIsAcceptedExactlyOnce() throws Exception {
        SequenceTracker tracker = new SequenceTracker();

        assertEquals(
                SequenceTracker.SequenceVerdict.ACCEPT,
                tracker.check("sensor-1", 5)
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<SequenceTracker.SequenceVerdict> task = () -> {
            ready.countDown();
            start.await();
            return tracker.check("sensor-1", 6);
        };

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            Future<SequenceTracker.SequenceVerdict> first =
                    executor.submit(task);

            Future<SequenceTracker.SequenceVerdict> second =
                    executor.submit(task);

            ready.await();
            start.countDown();

            List<SequenceTracker.SequenceVerdict> verdicts =
                    List.of(first.get(), second.get());

            long accepts =
                    verdicts.stream()
                            .filter(v ->
                                    v == SequenceTracker.SequenceVerdict.ACCEPT)
                            .count();

            long duplicates =
                    verdicts.stream()
                            .filter(v ->
                                    v == SequenceTracker.SequenceVerdict.DUPLICATE)
                            .count();

            assertEquals(1, accepts);
            assertEquals(1, duplicates);
            assertEquals(1, tracker.getDuplicateCount("sensor-1"));
        } finally {
            executor.shutdownNow();
        }
    }
}