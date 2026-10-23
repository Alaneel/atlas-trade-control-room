package com.gic.service;

import com.gic.model.TradeOperation;
import com.gic.model.TradeRequest;
import com.gic.model.TradeResult;
import org.springframework.stereotype.Service;

@Service
public class IdempotentTradeService {
    private final TradeService trades;
    private final TradeOperationStore operations;

    public IdempotentTradeService(TradeService trades, TradeOperationStore operations) {
        this.trades = trades;
        this.operations = operations;
    }

    public Submission submit(TradeRequest request, String key) {
        TradeOperationStore.Claim claim = operations.acquire(key, request);
        TradeOperation operation = claim.getOperation();
        if (!claim.isOwner()) {
            if ("COMPLETED".equals(operation.getStatus())) {
                return new Submission(new TradeResult(operation.getResultStatus(), operation.getResultMessage()),
                        operation.getId(), true, false);
            }
            String message = "RECONCILIATION_REQUIRED".equals(operation.getStatus())
                    ? "The original attempt requires reconciliation. Keep this key; submitting a new key may duplicate execution."
                    : "The original attempt is still pending. Retry with this same key and unchanged request.";
            return new Submission(new TradeResult("IN_PROGRESS", message), operation.getId(), true, true);
        }

        try {
            TradeResult result = trades.processTrade(request, operation.getId());
            operations.complete(operation.getId(), result);
            return new Submission(result, operation.getId(), false, false);
        } catch (RuntimeException ex) {
            // Never re-execute after an uncertain execution, history write, or
            // completion write. Even if marking fails, the insert still holds the key.
            try {
                operations.requireReconciliation(operation.getId());
            } catch (RuntimeException markingFailure) {
                ex.addSuppressed(markingFailure);
            }
            throw ex;
        }
    }

    public static final class Submission {
        private final TradeResult result;
        private final String operationId;
        private final boolean replayed;
        private final boolean pending;

        public Submission(TradeResult result, String operationId, boolean replayed, boolean pending) {
            this.result = result;
            this.operationId = operationId;
            this.replayed = replayed;
            this.pending = pending;
        }

        public TradeResult getResult() { return result; }
        public String getOperationId() { return operationId; }
        public boolean isReplayed() { return replayed; }
        public boolean isPending() { return pending; }
    }
}
