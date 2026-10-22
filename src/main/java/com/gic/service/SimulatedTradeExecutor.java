package com.gic.service;

import com.gic.model.TradeRequest;
import org.springframework.stereotype.Component;

@Component
public class SimulatedTradeExecutor implements TradeExecutor {
    @Override
    public boolean execute(TradeRequest request) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        return Math.random() < 0.9;
    }
}
