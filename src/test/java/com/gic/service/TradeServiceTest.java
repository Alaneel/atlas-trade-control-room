package com.gic.service;

import com.gic.model.Instrument;
import com.gic.model.InstrumentVerificationRequest;
import com.gic.model.InstrumentVerificationResult;
import com.gic.model.TradeHistory;
import com.gic.model.TradeRequest;
import com.gic.repository.TradeHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {
    @Mock private InstrumentService instruments;
    @Mock private LimitService limits;
    @Mock private TradeHistoryRepository history;
    @Mock private TradeExecutor executor;
    private TradeService service;
    private TradeRequest request;

    @BeforeEach
    void setUp() {
        service = new TradeService(instruments, limits, history, executor);
        InstrumentVerificationRequest definition = new InstrumentVerificationRequest();
        definition.setInstrumentGroup("Bonds");
        request = new TradeRequest(definition, "TEST", 100.0, false);
    }

    @Test
    void invalidInstrumentIsRecordedWithoutReservingOrExecuting() {
        verification(false, false);
        assertEquals("REJECTED", service.processTrade(request).getStatus());
        verifyNoInteractions(limits, executor);
        assertHistory("REJECTED");
    }

    @Test
    void unapprovedDepartmentIsRecordedWithoutReservingOrExecuting() {
        verification(true, false);
        assertEquals("REJECTED", service.processTrade(request).getStatus());
        verifyNoInteractions(limits, executor);
        assertHistory("REJECTED");
    }

    @Test
    void exhaustedCapacityCannotReachExecution() {
        verification(true, true);
        when(limits.checkAndReserveLimit("TEST", "Bonds", 100.0)).thenReturn(false);
        assertEquals("REJECTED", service.processTrade(request).getStatus());
        verifyNoInteractions(executor);
        verify(limits, never()).releaseLimitReservation(any(), any(), any());
        assertHistory("REJECTED");
    }

    @Test
    void successfulExecutionConsumesOnlyTheReservation() {
        verification(true, true);
        when(limits.checkAndReserveLimit("TEST", "Bonds", 100.0)).thenReturn(true);
        when(executor.execute(request)).thenReturn(true);
        assertEquals("EXECUTED", service.processTrade(request).getStatus());
        InOrder order = inOrder(limits, executor, history);
        order.verify(limits).checkAndReserveLimit("TEST", "Bonds", 100.0);
        order.verify(executor).execute(request);
        order.verify(limits).confirmLimitUsage("TEST", "Bonds", 100.0);
        order.verify(history).save(any(TradeHistory.class));
        verify(limits, never()).releaseLimitReservation(any(), any(), any());
        assertHistory("EXECUTED");
    }

    @Test
    void failedExecutionReleasesExactlyOnceBeforeRecordingFailure() {
        verification(true, true);
        when(limits.checkAndReserveLimit("TEST", "Bonds", 100.0)).thenReturn(true);
        when(executor.execute(request)).thenReturn(false);
        assertEquals("FAILED", service.processTrade(request).getStatus());
        InOrder order = inOrder(limits, executor, history);
        order.verify(limits).checkAndReserveLimit("TEST", "Bonds", 100.0);
        order.verify(executor).execute(request);
        order.verify(limits).releaseLimitReservation("TEST", "Bonds", 100.0);
        order.verify(history).save(any(TradeHistory.class));
        verify(limits, times(1)).releaseLimitReservation("TEST", "Bonds", 100.0);
        verify(limits, never()).confirmLimitUsage(any(), any(), any());
        assertHistory("FAILED");
    }

    @Test
    void unknownExecutionOutcomeRetainsCapacityForReconciliation() {
        verification(true, true);
        when(limits.checkAndReserveLimit("TEST", "Bonds", 100.0)).thenReturn(true);
        when(executor.execute(request)).thenThrow(new IllegalStateException("Outcome unknown"));
        assertThrows(IllegalStateException.class, () -> service.processTrade(request));
        verify(limits, never()).releaseLimitReservation(any(), any(), any());
        verifyNoInteractions(history);
    }

    private void verification(boolean valid, boolean approved) {
        Instrument instrument = new Instrument();
        instrument.setInstrumentGroup("Bonds");
        InstrumentVerificationResult result = new InstrumentVerificationResult();
        result.setValid(valid);
        result.setApprovedForDepartment(approved);
        result.setInstrument(instrument);
        when(instruments.verifyInstrument(request.getInstrumentVerificationRequest())).thenReturn(result);
    }

    private void assertHistory(String status) {
        ArgumentCaptor<TradeHistory> saved = ArgumentCaptor.forClass(TradeHistory.class);
        verify(history).save(saved.capture());
        assertEquals(status, saved.getValue().getStatus());
        assertEquals("TEST", saved.getValue().getCounterparty());
        assertEquals(100.0, saved.getValue().getAmount());
        assertNotNull(saved.getValue().getTimestamp());
    }
}
