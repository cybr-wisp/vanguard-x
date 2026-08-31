package com.vanguard.tracking.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackingEvaluatorTest {

    @Test
    @DisplayName("Perfect tracking yields zero RMSE")
    void perfectTracking() {
        TrackingEvaluator eval = new TrackingEvaluator();
        for (int i = 0; i < 50; i++) {
            eval.record("TRK-1", "TGT-1", i * 100,
                    100 + i, 200 + i, 10, 5,
                    100 + i, 200 + i, 10, 5); // est == truth
            eval.recordAssociation("TRK-1", "TGT-1");
        }
        var result = eval.evaluate();
        assertEquals(0.0, result.positionRmse(), 1e-12);
        assertEquals(0.0, result.velocityRmse(), 1e-12);
        assertEquals(1.0, result.associationAccuracy(), 1e-12);
        assertEquals(0, result.trackFragmentation());
        assertEquals(0, result.falseTracks());
    }

    @Test
    @DisplayName("Position RMSE computed correctly with known error")
    void knownError() {
        TrackingEvaluator eval = new TrackingEvaluator();
        // Constant 3-4-5 error triangle: pos error = 5m each sample
        for (int i = 0; i < 10; i++) {
            eval.record("TRK-1", "TGT-1", i * 100,
                    103.0, 204.0, 10, 5,
                    100.0, 200.0, 10, 5);
        }
        var result = eval.evaluate();
        assertEquals(5.0, result.positionRmse(), 1e-9);
        assertEquals(0.0, result.velocityRmse(), 1e-9);
    }

    @Test
    @DisplayName("Fragmentation detected when one truth maps to multiple tracks")
    void fragmentation() {
        TrackingEvaluator eval = new TrackingEvaluator();
        // Same truth target assigned to two different canonical tracks
        eval.recordAssociation("TRK-1", "TGT-A");
        eval.recordAssociation("TRK-2", "TGT-A");
        eval.record("TRK-1", "TGT-A", 0, 0, 0, 0, 0, 0, 0, 0, 0);
        eval.record("TRK-2", "TGT-A", 100, 0, 0, 0, 0, 0, 0, 0, 0);

        var result = eval.evaluate();
        assertEquals(1, result.trackFragmentation(), "TGT-A fragmented into 2 tracks");
    }

    @Test
    @DisplayName("False tracks counted when assigned to clutter")
    void falseTracks() {
        TrackingEvaluator eval = new TrackingEvaluator();
        eval.recordAssociation("TRK-1", "TGT-REAL");
        eval.recordAssociation("TRK-2", "__FALSE__");
        eval.record("TRK-1", "TGT-REAL", 0, 0, 0, 0, 0, 0, 0, 0, 0);
        eval.record("TRK-2", "__FALSE__", 0, 50, 50, 0, 0, 0, 0, 0, 0);

        var result = eval.evaluate();
        assertEquals(1, result.falseTracks());
    }

    @Test
    @DisplayName("Wrong association lowers accuracy")
    void wrongAssociation() {
        TrackingEvaluator eval = new TrackingEvaluator();
        eval.recordAssociation("TRK-1", "TGT-A"); // sets majority truth
        eval.recordAssociation("TRK-1", "TGT-A"); // correct
        eval.recordAssociation("TRK-1", "TGT-B"); // wrong

        var result = eval.evaluate();
        // 2 correct out of 3
        assertEquals(2.0 / 3.0, result.associationAccuracy(), 1e-9);
    }

    @Test
    @DisplayName("Empty evaluator returns zeroed result")
    void emptyEval() {
        var result = new TrackingEvaluator().evaluate();
        assertEquals(0, result.totalSamples());
    }

    @Test
    @DisplayName("Reset clears all data")
    void resetClears() {
        TrackingEvaluator eval = new TrackingEvaluator();
        eval.record("TRK-1", "TGT-1", 0, 0, 0, 0, 0, 0, 0, 0, 0);
        eval.reset();
        assertEquals(0, eval.getSamples().size());
    }
}
