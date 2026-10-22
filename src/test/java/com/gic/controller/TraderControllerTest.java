package com.gic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gic.model.InstrumentVerificationRequest;
import com.gic.model.TradeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TraderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testGetInstruments() throws Exception {
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
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetTradeHistory() throws Exception {
        mockMvc.perform(get("/api/trader/trades"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
}
