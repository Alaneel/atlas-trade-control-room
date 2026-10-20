package com.gic.controller;

import com.gic.model.*;
import com.gic.service.*;
import com.gic.reliability.TradeEvent;
import com.gic.reliability.TradeOrder;
import java.util.List;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trader")
public class TraderController {
    @Autowired private InstrumentService instrumentService;
    @Autowired private LimitService limitService;
    @Autowired private ApprovalService approvalService;
    @Autowired private TradeService tradeService;

    @PostMapping("/verify-instrument")
    public ResponseEntity<?> verifyInstrument(@Valid @RequestBody InstrumentVerificationRequest request) {
        return ResponseEntity.ok(instrumentService.verifyInstrument(request));
    }

    @GetMapping("/instruments")
    public ResponseEntity<List<String>> getAvailableInstruments() {
        return ResponseEntity.ok(instrumentService.getAllInstrumentIds());
    }

    @PostMapping("/approval-request")
    public ResponseEntity<ApprovalRequest> createApprovalRequest(@Valid @RequestBody InstrumentVerificationRequest request) {
        return ResponseEntity.ok(approvalService.createApprovalRequest(request));
    }

    @GetMapping("/limit/{counterparty}/{instrumentGroup}")
    public ResponseEntity<Limit> getAvailableLimit(@PathVariable String counterparty, @PathVariable String instrumentGroup,
                                                  @RequestParam(defaultValue = "USD") String currency) {
        return ResponseEntity.ok(limitService.getAvailableLimit(counterparty, instrumentGroup, currency));
    }

    @PostMapping("/trade")
    public ResponseEntity<TradeResult> executeTrade(@RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody TradeRequest request) {
        return response(tradeService.processTrade(key, request));
    }

    @GetMapping("/trades")
    public ResponseEntity<List<TradeOrder>> getTradeHistory() { return ResponseEntity.ok(tradeService.getTradeHistory()); }

    @GetMapping("/trades/{id}")
    public ResponseEntity<TradeOrder> getTrade(@PathVariable String id) { return ResponseEntity.ok(tradeService.get(id)); }

    @GetMapping("/trades/{id}/events")
    public ResponseEntity<List<TradeEvent>> events(@PathVariable String id) { return ResponseEntity.ok(tradeService.events(id)); }

    @PostMapping("/trades/{id}/reconcile")
    public ResponseEntity<TradeResult> reconcile(@PathVariable String id) { return response(tradeService.reconcile(id)); }

    private ResponseEntity<TradeResult> response(TradeResult result) {
        boolean terminal = "EXECUTED".equals(result.getStatus()) || "REJECTED".equals(result.getStatus());
        return ResponseEntity.status(terminal ? 200 : 202).body(result);
    }
}
