package com.gic.reliability;

import java.util.Optional;

/** Stable trade ID is the venue idempotency key. Query must have no side effects. */
public interface ExecutionGateway {
    ExecutionReceipt submit(TradeOrder trade);
    Optional<ExecutionReceipt> query(String tradeId);
}
