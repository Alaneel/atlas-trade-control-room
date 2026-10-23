package com.gic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gic.model.InstrumentVerificationRequest;
import com.gic.model.TradeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.gic.service.*;
import static org.mockito.Mockito.*;
import java.util.Collections;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TraderController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class TraderControllerTest {
    @MockBean private InstrumentService instrumentService;
    @MockBean private LimitService limitService;
    @MockBean private ApprovalService approvalService;
    @MockBean private TradeService tradeService;


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testGetInstruments() throws Exception {
        when(instrumentService.getAllInstrumentIds()).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/api/trader/instruments"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void testVerifyInstrument() throws Exception {
        InstrumentVerificationRequest request = new InstrumentVerificationRequest();
        request.setInstrumentGroup("EQUITY");
        request.setInstrument("AAPL");
        request.setSettlementCurrency("USD");
        request.setTradeCurrency("USD");
        request.setCountry("US");
        request.setExchange("NYSE");
        request.setDepartment("Trading");

        mockMvc.perform(post("/api/trader/verify-instrument")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void testGetLimit() throws Exception {
        mockMvc.perform(get("/api/trader/limit/COUNTERPARTY1/EQUITY"))
                .andExpect(status().isOk());
    }

    @Test
    void testGetTradeHistory() throws Exception {
        when(tradeService.getTradeHistory()).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/api/trader/trades"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    private TradeRequest validTrade() {
        InstrumentVerificationRequest instrument = new InstrumentVerificationRequest();
        instrument.setInstrumentGroup("EQUITY");
        instrument.setInstrument("AAPL");
        instrument.setTradeCurrency("USD");
        instrument.setSettlementCurrency("USD");
        instrument.setCountry("US");
        instrument.setExchange("NASDAQ");
        instrument.setDepartment("GLOBAL_MARKETS");
        return new TradeRequest(instrument, "NORTHSTAR", new java.math.BigDecimal("10.00"), true);
    }

    @Test
    void missingIdempotencyHeaderReturns400BeforeService() throws Exception {
        mockMvc.perform(post("/api/trader/trade").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(validTrade()))).andExpect(status().isBadRequest());
        verifyNoInteractions(tradeService);
    }

    @Test
    void unconfirmedOrFractionalCentRequestReturns400() throws Exception {
        TradeRequest request = validTrade();
        request.setConfirmed(false);
        mockMvc.perform(post("/api/trader/trade").header("Idempotency-Key", "request-1")
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
        request.setConfirmed(true);
        request.setAmount(new java.math.BigDecimal("0.001"));
        mockMvc.perform(post("/api/trader/trade").header("Idempotency-Key", "request-1")
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(tradeService);
    }

    @Test
    void uncertainSubmissionReturns202AndStableTradeReference() throws Exception {
        when(tradeService.processTrade(eq("request-1"), any())).thenReturn(
            new com.gic.model.TradeResult("UNKNOWN", "Capacity held", "trade-1"));
        mockMvc.perform(post("/api/trader/trade").header("Idempotency-Key", "request-1")
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(validTrade())))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.tradeId").value("trade-1"))
            .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void conflictRemains409AndMalformedJsonRemains400() throws Exception {
        when(tradeService.processTrade(eq("request-1"), any())).thenThrow(
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Key conflict"));
        mockMvc.perform(post("/api/trader/trade").header("Idempotency-Key", "request-1")
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(validTrade())))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/trader/trade").header("Idempotency-Key", "request-1")
            .contentType(MediaType.APPLICATION_JSON).content("{broken"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reconciliationUsesDedicatedReadOnlyServiceAndReturnsTerminal200() throws Exception {
        when(tradeService.reconcile("trade-1")).thenReturn(
            new com.gic.model.TradeResult("EXECUTED", "Receipt verified", "trade-1"));
        mockMvc.perform(post("/api/trader/trades/trade-1/reconcile"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTED"));
        verify(tradeService).reconcile("trade-1");
        verify(tradeService, never()).processTrade(any(), any());
    }
}
