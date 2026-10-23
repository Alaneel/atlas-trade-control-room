package com.gic.reliability.unit;

import com.gic.reliability.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeWorkerTest {
    private final ReliableTradeStore store = mock(ReliableTradeStore.class);
    private final ExecutionGateway gateway = mock(ExecutionGateway.class);
    private final TradeWorker worker = new TradeWorker(store, gateway, 1000);

    private TradeOrder submitted() {
        TradeOrder t = TradeOrder.proposal("key", TradeValidationTest.request("10"), "EQUITY", "USD", null);
        t.setStatus(TradeStatus.SUBMITTED); return t;
    }

    @Test void queryReceiptBeforeAnyResubmission() {
        TradeOrder t = submitted(); ExecutionReceipt receipt = new ExecutionReceipt();
        when(store.claim(t.getId(), 1000)).thenReturn(t);
        when(gateway.query(t.getId())).thenReturn(Optional.of(receipt));
        when(store.settle(t, receipt)).thenReturn(t);
        assertSame(t, worker.dispatch(t.getId()));
        verify(gateway, never()).submit(any());
    }

    @Test void timeoutPreservesReservationAsUnknown() {
        TradeOrder t = submitted();
        when(store.claim(t.getId(), 1000)).thenReturn(t);
        when(gateway.query(t.getId())).thenReturn(Optional.empty());
        when(gateway.submit(t)).thenThrow(new ExecutionUncertainException("response lost"));
        when(store.uncertain(eq(t), anyString(), eq(1000L))).thenReturn(t);
        assertSame(t, worker.dispatch(t.getId()));
        verify(store, never()).settle(any(), any());
        verify(store).uncertain(eq(t), anyString(), eq(1000L));
    }

    @Test void manualReconciliationNeverSubmitsEvenWithoutReceipt() {
        TradeOrder t = submitted(); when(store.get(t.getId())).thenReturn(t);
        when(gateway.query(t.getId())).thenReturn(Optional.empty());
        assertSame(t, worker.reconcile(t.getId()));
        verify(gateway, never()).submit(any()); verify(store, never()).claim(anyString(), anyLong());
    }

    @Test void databaseFailureAfterReceiptIsNotMisclassifiedAsVenueRejection() {
        TradeOrder t = submitted(); ExecutionReceipt receipt = new ExecutionReceipt();
        when(store.claim(t.getId(), 1000)).thenReturn(t);
        when(gateway.query(t.getId())).thenReturn(Optional.of(receipt));
        when(store.settle(t, receipt)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> worker.dispatch(t.getId()));
        verify(store, never()).uncertain(any(), anyString(), anyLong());
    }

    @Test void leasedTradeIsOnlyRead() {
        TradeOrder t = submitted(); when(store.get(t.getId())).thenReturn(t);
        assertSame(t, worker.dispatch(t.getId())); verifyNoInteractions(gateway);
    }

    @Test void refusesUnboundedLease() {
        assertThrows(IllegalArgumentException.class, () -> new TradeWorker(store, gateway, 0));
        assertThrows(IllegalArgumentException.class, () -> new TradeWorker(store, gateway, 300001));
    }
}
