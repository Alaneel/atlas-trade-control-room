package com.gic.service;

import com.gic.model.TradeRequest;

/** A simulated execution boundary; replacing it requires an explicit broker outcome contract. */
public interface TradeExecutor {
    boolean execute(TradeRequest request);
}
