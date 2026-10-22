package com.gic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gic.exception.GlobalExceptionHandler;
import com.gic.model.InstrumentVerificationRequest;
import com.gic.model.TradeRequest;
import com.gic.service.ApprovalService;
import com.gic.service.InstrumentService;
import com.gic.service.LimitService;
import com.gic.service.TradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Boundary tests deliberately avoid MongoDB: invalid input must never reach it. */
class TraderValidationTest {
    private final TradeService trades = mock(TradeService.class);
    private final InstrumentService instruments = mock(InstrumentService.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        TraderController controller = new TraderController();
        ReflectionTestUtils.setField(controller, "tradeService", trades);
        ReflectionTestUtils.setField(controller, "instrumentService", instruments);
        ReflectionTestUtils.setField(controller, "approvalService", approvals);
        ReflectionTestUtils.setField(controller, "limitService", mock(LimitService.class));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = standaloneSetup(controller).setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {0, -1, -1000000, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void invalidAmountsNeverReachTrading(Double amount) throws Exception {
        TradeRequest request = validTrade();
        request.setAmount(amount);
        mvc.perform(post("/api/trader/trade").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("status").value(400));
        verifyNoInteractions(trades);
    }

    @Test
    void missingNestedInstrumentIsRejected() throws Exception {
        TradeRequest request = validTrade();
        request.setInstrumentVerificationRequest(null);
        mvc.perform(post("/api/trader/trade").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(trades);
    }

    @Test
    void blankInstrumentAttributeIsRejectedRecursively() throws Exception {
        TradeRequest request = validTrade();
        request.getInstrumentVerificationRequest().setDepartment(" ");
        mvc.perform(post("/api/trader/trade").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(trades);
    }

    @Test
    void blankCounterpartyIsRejected() throws Exception {
        TradeRequest request = validTrade();
        request.setCounterparty(" ");
        mvc.perform(post("/api/trader/trade").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(trades);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/trader/verify-instrument", "/api/trader/approval-request"})
    void emptyInstrumentDoesNotReachLookupOrApproval(String route) throws Exception {
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(instruments, approvals);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"amount\": {}}", ""})
    void malformedRequestReturnsClientError(String body) throws Exception {
        mvc.perform(post("/api/trader/trade").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("error").value("Invalid Request"));
        verifyNoInteractions(trades);
    }

    private TradeRequest validTrade() {
        InstrumentVerificationRequest instrument = new InstrumentVerificationRequest();
        instrument.setInstrumentGroup("Bonds");
        instrument.setInstrument("0% Convertible Bonds");
        instrument.setDepartment("RE");
        instrument.setCountry("ASIA PACIFIC REGION");
        instrument.setExchange("Colombo");
        instrument.setTradeCurrency("CHF");
        instrument.setSettlementCurrency("MXN");
        return new TradeRequest(instrument, "Sample counterparty", 100.0, false);
    }
}
