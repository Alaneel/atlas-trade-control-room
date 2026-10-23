package com.gic.controller;

import com.gic.model.Instrument;
import com.gic.model.InstrumentVerificationRequest;
import com.gic.model.InstrumentVerificationResult;
import com.gic.model.Limit;
import com.gic.model.TradeHistory;
import com.gic.model.TradeRequest;
import com.gic.repository.TradeHistoryRepository;
import com.gic.service.InstrumentService;
import com.gic.service.LimitService;
import com.gic.service.TradeExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the HTTP contract and durable Mongo admission guard together. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyFlowTest {

    private static final String COUNTERPARTY = "IDEMPOTENCY_TEST";
    private static final String GROUP = "EQUITY";

    @Autowired private MockMvc mvc;
    @Autowired private MongoTemplate mongo;
    @Autowired private LimitService limits;
    @MockBean private InstrumentService instruments;
    @MockBean private TradeExecutor executor;
    @SpyBean private TradeHistoryRepository history;

    @BeforeEach
    void prepareCapacityAndEligibility() {
        // FactoryBean-backed repository spies are not consistently picked up by
        // Boot's automatic mock reset. Clear the fault injected by another test.
        reset(history);
        mongo.remove(new Query(), Limit.class);
        mongo.remove(new Query(), TradeHistory.class);
        mongo.remove(new Query(), "trade_operations");

        Limit limit = new Limit();
        limit.setId("idempotency-limit");
        limit.setCounterparty(COUNTERPARTY);
        limit.setInstrumentGroup(GROUP);
        limit.setCurrency("USD");
        limit.setAvailableLimit(new BigDecimal("100.00"));
        mongo.save(limit);

        Instrument instrument = new Instrument();
        instrument.setId("idempotency-instrument");
        instrument.setInstrumentGroup(GROUP);
        instrument.setInstrument("AAPL");
        InstrumentVerificationResult eligible = new InstrumentVerificationResult();
        eligible.setInstrument(instrument);
        eligible.setValid(true);
        eligible.setApprovedForDepartment(true);
        when(instruments.verifyInstrument(any(InstrumentVerificationRequest.class))).thenReturn(eligible);
        when(executor.execute(any(TradeRequest.class))).thenReturn(true);
    }

    @Test
    void thirtyTwoConcurrentSubmissionsExecuteAndDebitOnlyOnce() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch pendingReturned = new CountDownLatch(31);
        when(executor.execute(any(TradeRequest.class))).thenAnswer(call -> {
            executions.incrementAndGet();
            ownerEntered.countDown();
            assertTrue(releaseOwner.await(30, TimeUnit.SECONDS));
            return true;
        });

        ExecutorService workers = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                futures.add(workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    MvcResult response = submit("same-parallel-trade", "1.00");
                    if (response.getResponse().getStatus() == 202) {
                        pendingReturned.countDown();
                    }
                    return response;
                }));
            }
            start.countDown();
            assertTrue(ownerEntered.await(10, TimeUnit.SECONDS));
            assertTrue(pendingReturned.await(20, TimeUnit.SECONDS),
                    "Every duplicate should return while the execution owner is still blocked");
            assertBalance("99.00");
            releaseOwner.countDown();

            int completed = 0;
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(10, TimeUnit.SECONDS);
                int status = result.getResponse().getStatus();
                assertTrue(status == 200 || status == 202);
                if (status == 200) {
                    completed++;
                    assertTrue(result.getResponse().getContentAsString().contains("EXECUTED"));
                } else {
                    assertTrue(result.getResponse().getContentAsString().contains("IN_PROGRESS"));
                }
            }
            assertEquals(1, completed);
            assertEquals(1, executions.get());
            assertBalance("99.00");
            assertEquals(1, mongo.count(new Query(), TradeHistory.class));
            assertEquals(1, mongo.count(new Query(), "trade_operations"));
        } finally {
            releaseOwner.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void aCompletedSubmissionReplaysItsResponseWithoutAnotherSideEffect() throws Exception {
        MvcResult first = mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "completed-trade")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("12.34")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn();

        MvcResult replay = mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "completed-trade")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("12.34")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();

        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertNotNull(first.getResponse().getHeader("X-Trade-Operation-Id"));
        assertEquals(first.getResponse().getHeader("X-Trade-Operation-Id"),
                replay.getResponse().getHeader("X-Trade-Operation-Id"));
        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("87.66");
        assertEquals(1, mongo.count(new Query(), TradeHistory.class));
    }

    @Test
    void reusingAKeyWithChangedPayloadReturnsConflictBeforeAnyExtraDebit() throws Exception {
        assertEquals(200, submit("payload-conflict", "1.00").getResponse().getStatus());
        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "payload-conflict")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("2.00")))
                .andExpect(status().isConflict());

        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("99.00");
        assertEquals(1, mongo.count(new Query(), TradeHistory.class));
    }

    @Test
    void equivalentDecimalScalesHaveTheSameRequestFingerprint() throws Exception {
        assertEquals(200, submit("decimal-canonicalization", "1.0").getResponse().getStatus());
        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "decimal-canonicalization")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("1.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(header().string("Idempotency-Replayed", "true"));

        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("99.00");
        assertEquals(1, mongo.count(new Query(), "trade_operations"));
    }

    @Test
    void anUnknownExecutionOutcomeKeepsCapacityHeldAndNeverReexecutesOnRetry() throws Exception {
        when(executor.execute(any(TradeRequest.class)))
                .thenThrow(new IllegalStateException("Venue disconnected after accepting request"));
        assertEquals(500, submit("unknown-execution", "10.10").getResponse().getStatus());
        assertBalance("89.90");

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/trader/trade")
                            .header("Idempotency-Key", "unknown-execution")
                            .contentType(MediaType.APPLICATION_JSON).content(payload("10.10")))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        }

        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("89.90");
        assertEquals(0, mongo.count(new Query(), TradeHistory.class));
        assertEquals(1, mongo.count(new Query(), "trade_operations"));
    }

    @Test
    void aKnownFailureIsReplayedWithoutRefundingTheReservationTwice() throws Exception {
        when(executor.execute(any(TradeRequest.class))).thenReturn(false);
        assertEquals(200, submit("failed-execution", "10.10").getResponse().getStatus());
        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "failed-execution")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("10.10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(header().string("Idempotency-Replayed", "true"));

        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("100.00");
        assertEquals(1, mongo.count(new Query(), TradeHistory.class));
    }

    @Test
    void aHistoryWriteFailureAfterExecutionCannotCauseASecondVenueSubmission() throws Exception {
        doThrow(new IllegalStateException("History store unavailable"))
                .when(history).save(any(TradeHistory.class));

        assertEquals(500, submit("history-write-failure", "10.10").getResponse().getStatus());
        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "history-write-failure")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("10.10")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("89.90");
        assertEquals(0, mongo.count(new Query(), TradeHistory.class));
        assertEquals(1, mongo.count(new Query(), "trade_operations"));
    }

    @Test
    void invalidKeysFailBeforeCreatingAnOperationOrReservingCapacity() throws Exception {
        for (String key : new String[] {"", "contains a space", "x".repeat(129)}) {
            mvc.perform(post("/api/trader/trade")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(payload("10.10")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
        }

        verify(executor, never()).execute(any(TradeRequest.class));
        assertBalance("100.00");
        assertEquals(0, mongo.count(new Query(), TradeHistory.class));
        assertEquals(0, mongo.count(new Query(), "trade_operations"));
    }

    @Test
    void requestsWithoutKeysRemainIndependentSubmissions() throws Exception {
        MvcResult first = mvc.perform(post("/api/trader/trade")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("12.34")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn();
        MvcResult second = mvc.perform(post("/api/trader/trade")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("12.34")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn();

        assertNotEquals(first.getResponse().getHeader("X-Trade-Operation-Id"),
                second.getResponse().getHeader("X-Trade-Operation-Id"));
        verify(executor, times(2)).execute(any(TradeRequest.class));
        assertBalance("75.32");
        assertEquals(2, mongo.count(new Query(), TradeHistory.class));
        assertEquals(2, mongo.count(new Query(), "trade_operations"));
    }

    @Test
    void aMalformedUnicodePayloadCannotReplayAQuestionMarkPayload() throws Exception {
        String ordinary = payload("1.00").replace("\"AAPL\"", "\"AAPL?\"");
        // Construct the JSON escape directly: ObjectMapper must not pre-normalize it.
        String malformed = payload("1.00").replace("\"AAPL\"", "\"AAPL" + "\\" + "uD800\"");
        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "question-mark-unicode")
                        .contentType(MediaType.APPLICATION_JSON).content(ordinary))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "question-mark-unicode")
                        .contentType(MediaType.APPLICATION_JSON).content(malformed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TRADE_TEXT"))
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("99.00");
        assertEquals(1, mongo.count(new Query(), TradeHistory.class));
        assertEquals(1, mongo.count(new Query(), "trade_operations"));
    }

    @Test
    void unpairedSurrogatesCannotReserveCapacityOrPoisonANewKey() throws Exception {
        for (String surrogate : new String[] {"uD800", "uDC00"}) {
            String malformed = payload("1.00").replace("\"AAPL\"", "\"AAPL" + "\\" + surrogate + "\"");
            mvc.perform(post("/api/trader/trade")
                            .header("Idempotency-Key", "invalid-unicode-first")
                            .contentType(MediaType.APPLICATION_JSON).content(malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_TRADE_TEXT"));
        }
        verify(executor, never()).execute(any(TradeRequest.class));
        assertBalance("100.00");
        assertEquals(0, mongo.count(new Query(), TradeHistory.class));
        assertEquals(0, mongo.count(new Query(), "trade_operations"));

        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "invalid-unicode-first")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("1.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(header().string("Idempotency-Replayed", "false"));
        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("99.00");
        assertEquals(1, mongo.count(new Query(), "trade_operations"));
    }

    @Test
    void validUnicodeEmojiRemainEligibleForSubmissionAndReplay() throws Exception {
        String escapedEmoji = payload("1.00").replace("\"AAPL\"",
                "\"AAPL" + "\\" + "uD83D" + "\\" + "uDE80\"");
        String plainEmoji = payload("1.0").replace("\"AAPL\"",
                "\"AAPL" + new String(Character.toChars(0x1f680)) + "\"");
        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "valid-emoji")
                        .contentType(MediaType.APPLICATION_JSON).content(escapedEmoji))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
        mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", "valid-emoji")
                        .contentType(MediaType.APPLICATION_JSON).content(plainEmoji))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(header().string("Idempotency-Replayed", "true"));
        verify(executor, times(1)).execute(any(TradeRequest.class));
        assertBalance("99.00");
        assertEquals(1, mongo.count(new Query(), TradeHistory.class));
        assertEquals(1, mongo.count(new Query(), "trade_operations"));
    }

    private MvcResult submit(String key, String amount) throws Exception {
        return mvc.perform(post("/api/trader/trade")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(payload(amount)))
                .andReturn();
    }

    private String payload(String amount) {
        return "{\"counterparty\":\"" + COUNTERPARTY + "\",\"amount\":" + amount
                + ",\"instrumentVerificationRequest\":{\"instrumentGroup\":\"" + GROUP
                + "\",\"instrument\":\"AAPL\",\"settlementCurrency\":\"USD\","
                + "\"tradeCurrency\":\"USD\",\"country\":\"US\",\"exchange\":\"NYSE\","
                + "\"department\":\"Trading\"}}";
    }

    private void assertBalance(String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(
                limits.getAvailableLimit(COUNTERPARTY, GROUP).getAvailableLimit()));
    }
}
