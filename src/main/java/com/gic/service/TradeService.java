package com.gic.service;

import com.gic.model.InstrumentVerificationResult;
import com.gic.model.TradeRequest;
import com.gic.model.TradeResult;
import com.gic.reliability.ReliableTradeStore;
import com.gic.reliability.TradeEvent;
import com.gic.reliability.TradeOrder;
import com.gic.reliability.TradeWorker;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TradeService {
    private final InstrumentService instruments;
    private final ReliableTradeStore store;
    private final TradeWorker worker;

    public TradeService(InstrumentService instruments, ReliableTradeStore store, TradeWorker worker) {
        this.instruments = instruments;
        this.store = store;
        this.worker = worker;
    }

    public TradeResult processTrade(String key, TradeRequest request) {
        // Validate before reading any instrument fields, including calls outside HTTP validation.
        TradeOrder proposal = TradeOrder.proposal(key, request,
            request != null && request.getInstrumentVerificationRequest() != null
                ? request.getInstrumentVerificationRequest().getInstrumentGroup() : null,
            request != null && request.getInstrumentVerificationRequest() != null
                ? request.getInstrumentVerificationRequest().getTradeCurrency() : null, null);
        java.util.Optional<TradeOrder> previous = store.existing(proposal);
        if (previous.isPresent()) {
            TradeOrder trade = previous.get();
            return result(trade.getStatus().terminal() ? trade : worker.dispatch(trade.getId()));
        }
        InstrumentVerificationResult verification = instruments.verifyInstrument(request.getInstrumentVerificationRequest());
        if (!verification.isValid() || !verification.isApprovedForDepartment()) {
            proposal.setStatus(com.gic.reliability.TradeStatus.REJECTED);
            proposal.setMessage("Instrument combination is not approved for this department");
        }
        TradeOrder trade = store.create(proposal);
        return result(trade.getStatus().terminal() ? trade : worker.dispatch(trade.getId()));
    }

    public TradeOrder get(String id) { return store.get(id); }
    public TradeResult reconcile(String id) { return result(worker.reconcile(id)); }
    public List<TradeOrder> getTradeHistory() { return store.list(); }
    public List<TradeEvent> events(String id) { return store.events(id); }

    private TradeResult result(TradeOrder trade) {
        return new TradeResult(trade.getStatus().name(), trade.getMessage(), trade.getId());
    }
}
