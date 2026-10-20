package com.gic.reliability;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TradeWorker {
    private static final Logger LOG = LoggerFactory.getLogger(TradeWorker.class);
    private final ReliableTradeStore store;
    private final ExecutionGateway gateway;
    private final long leaseMillis;
    @Value("${atlas.recovery.enabled:true}") private boolean recoveryEnabled = true;

    public TradeWorker(ReliableTradeStore store, ExecutionGateway gateway,
                       @Value("${atlas.recovery.lease-ms:5000}") long leaseMillis) {
        if (leaseMillis < 1 || leaseMillis > 300000) { throw new IllegalArgumentException("lease-ms must be 1..300000"); }
        this.store = store;
        this.gateway = gateway;
        this.leaseMillis = leaseMillis;
    }

    public TradeOrder dispatch(String id) {
        TradeOrder claimed = store.claim(id, leaseMillis);
        if (claimed == null) { return store.get(id); }
        ExecutionReceipt receipt;
        try {
            Optional<ExecutionReceipt> known = gateway.query(id);
            // A retry is safe only because this gateway contract deduplicates stable trade IDs.
            receipt = known.orElseGet(() -> gateway.submit(claimed));
        } catch (RuntimeException ex) {
            LOG.warn("Execution outcome uncertain for trade {} ({})", id, ex.getClass().getSimpleName());
            return store.uncertain(claimed, "Execution outcome unknown; capacity remains reserved", leaseMillis);
        }
        // A database failure here leaves SUBMITTED durable; the next worker queries the receipt.
        return store.settle(claimed, receipt);
    }

    /** Manual reconciliation reads the venue only; it never resubmits an order. */
    public TradeOrder reconcile(String id) {
        TradeOrder current = store.get(id);
        if (current.getStatus().terminal()) { return current; }
        Optional<ExecutionReceipt> receipt = gateway.query(id);
        if (!receipt.isPresent()) { return current; }
        TradeOrder claimed = store.claimForReconciliation(id, leaseMillis);
        return claimed == null ? store.get(id) : store.settle(claimed, receipt.get());
    }

    @Scheduled(fixedDelayString = "${atlas.recovery.poll-ms:1000}")
    public void recover() {
        if (!recoveryEnabled) { return; }
        try {
            for (TradeOrder trade : store.due(25)) {
                try { dispatch(trade.getId()); }
                catch (RuntimeException ex) { LOG.warn("Recovery deferred for trade {} ({})", trade.getId(), ex.getClass().getSimpleName()); }
            }
        } catch (RuntimeException ex) { LOG.warn("Recovery scan deferred ({})", ex.getClass().getSimpleName()); }
    }
}
