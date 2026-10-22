package com.gic.service;

import com.gic.model.*;
import com.gic.repository.TradeHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TradeService {

    private final InstrumentService instrumentService;
    private final LimitService limitService;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final TradeExecutor tradeExecutor;

    public TradeService(InstrumentService instrumentService, LimitService limitService,
                        TradeHistoryRepository tradeHistoryRepository, TradeExecutor tradeExecutor) {
        this.instrumentService = instrumentService;
        this.limitService = limitService;
        this.tradeHistoryRepository = tradeHistoryRepository;
        this.tradeExecutor = tradeExecutor;
    }

    public TradeResult processTrade(TradeRequest tradeRequest) {
        InstrumentVerificationResult verificationResult = instrumentService.verifyInstrument(tradeRequest.getInstrumentVerificationRequest());

        if (!verificationResult.isValid()) {
            saveTrade(tradeRequest, "REJECTED");
            return new TradeResult("REJECTED", "Invalid instrument combination");
        }

        if (!verificationResult.isApprovedForDepartment()) {
            saveTrade(tradeRequest, "REJECTED");
            return new TradeResult("REJECTED", "Instrument not approved for your department");
        }

        String instrumentGroup = verificationResult.getInstrument().getInstrumentGroup();
        boolean limitAvailable = limitService.checkAndReserveLimit(tradeRequest.getCounterparty(), instrumentGroup, tradeRequest.getAmount());

        if (!limitAvailable) {
            saveTrade(tradeRequest, "REJECTED");
            return new TradeResult("REJECTED", "Insufficient limit for the counterparty and instrument group");
        }

        // An exception has an unknown outcome: retain capacity rather than credit
        // it speculatively. Durable reconciliation requires a reservation ledger.
        boolean tradeExecuted = tradeExecutor.execute(tradeRequest);

        if (tradeExecuted) {
            limitService.confirmLimitUsage(tradeRequest.getCounterparty(), instrumentGroup, tradeRequest.getAmount());
            saveTrade(tradeRequest, "EXECUTED");
            return new TradeResult("EXECUTED", "Trade executed successfully");
        } else {
            limitService.releaseLimitReservation(tradeRequest.getCounterparty(), instrumentGroup, tradeRequest.getAmount());
            saveTrade(tradeRequest, "FAILED");
            return new TradeResult("FAILED", "Trade execution failed on the external platform");
        }
    }

    private void saveTrade(TradeRequest tradeRequest, String status) {
        TradeHistory tradeHistory = new TradeHistory();
        tradeHistory.setInstrumentVerificationRequest(tradeRequest.getInstrumentVerificationRequest());
        tradeHistory.setCounterparty(tradeRequest.getCounterparty());
        tradeHistory.setAmount(tradeRequest.getAmount());
        tradeHistory.setTimestamp(LocalDateTime.now());
        tradeHistory.setStatus(status);
        tradeHistoryRepository.save(tradeHistory);
    }

    public List<TradeHistory> getTradeHistory() {
        return tradeHistoryRepository.findAll();
    }
}
