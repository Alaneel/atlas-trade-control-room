package com.gic.controller;

import com.gic.model.*;
import com.gic.service.*;
import com.gic.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/trader")
public class TraderController {

    @Autowired
    private InstrumentService instrumentService;

    @Autowired
    private LimitService limitService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private TradeService tradeService;

    @Autowired
    private IdempotentTradeService idempotentTradeService;

    @PostMapping("/verify-instrument")
    public ResponseEntity<?> verifyInstrument(@Valid @RequestBody InstrumentVerificationRequest request) {
        InstrumentVerificationResult result = instrumentService.verifyInstrument(request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/instruments")
    public ResponseEntity<List<String>> getAvailableInstruments() {
        return ResponseEntity.ok(instrumentService.getAllInstrumentIds());
    }

    @PostMapping("/approval-request")
    public ResponseEntity<ApprovalRequest> createApprovalRequest(@Valid @RequestBody InstrumentVerificationRequest request) {
        ApprovalRequest approvalRequest = approvalService.createApprovalRequest(request);
        return ResponseEntity.ok(approvalRequest);
    }

    @GetMapping("/limit/{counterparty}/{instrumentGroup}")
    public ResponseEntity<Limit> getAvailableLimit(@PathVariable String counterparty, @PathVariable String instrumentGroup) {
        Limit limit = limitService.getAvailableLimit(counterparty, instrumentGroup);
        if (limit == null) {
            throw new ResourceNotFoundException("No limit exists for this counterparty and instrument group");
        }
        return ResponseEntity.ok(limit);
    }

    @PostMapping("/trade")
    public ResponseEntity<TradeResult> executeTrade(@Valid @RequestBody TradeRequest tradeRequest,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        IdempotentTradeService.Submission submission = idempotentTradeService.submit(tradeRequest, key);
        return ResponseEntity.status(submission.isPending() ? 202 : 200)
                .header("Idempotency-Replayed", Boolean.toString(submission.isReplayed()))
                .header("X-Trade-Operation-Id", submission.getOperationId())
                .body(submission.getResult());
    }

    @GetMapping("/trades")
    public ResponseEntity<List<TradeHistory>> getTradeHistory() {
        List<TradeHistory> tradeHistory = tradeService.getTradeHistory();
        return ResponseEntity.ok(tradeHistory);
    }
}
