package com.gic.service;

import com.gic.model.TradeRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedTradeExecutorTest {
    @Test
    void interruptedExecutionFailsAndPreservesCancellationSignal() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(new SimulatedTradeExecutor().execute(new TradeRequest()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
