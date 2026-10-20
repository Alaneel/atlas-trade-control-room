package com.gic.reliability.unit;

import com.gic.model.InstrumentVerificationRequest;
import com.gic.model.TradeRequest;
import com.gic.reliability.Money;
import com.gic.reliability.TradeOrder;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class TradeValidationTest {
    static TradeRequest request(String amount) {
        InstrumentVerificationRequest i = new InstrumentVerificationRequest();
        i.setInstrumentGroup("EQUITY"); i.setInstrument("Stock"); i.setTradeCurrency("USD");
        i.setSettlementCurrency("USD"); i.setCountry("US"); i.setExchange("NYSE"); i.setDepartment("Trading");
        return new TradeRequest(i, "CP", new BigDecimal(amount), true);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "0.001", "1.001", "1000000000", "1E+100"})
    void refusesOutOfRangeOrFractionalCents(String amount) {
        assertThrows(ResponseStatusException.class, () -> Money.amount(new BigDecimal(amount)));
    }

    @Test void nullAmountRejected() {
        assertThrows(ResponseStatusException.class, () -> Money.amount(null));
    }

    @Test void canonicalFingerprintPreservesBusinessIdentity() {
        TradeOrder a = TradeOrder.proposal("same-key", request("1"), "EQUITY", "USD", null);
        TradeOrder b = TradeOrder.proposal("same-key", request("1.00"), "EQUITY", "USD", null);
        assertEquals(a.getId(), b.getId());
        assertEquals(a.getRequestFingerprint(), b.getRequestFingerprint());
        assertEquals(new BigDecimal("1.00"), a.getAmount());
        TradeRequest different = request("1"); different.getInstrumentVerificationRequest().setDepartment("Other");
        assertNotEquals(a.getRequestFingerprint(), TradeOrder.proposal("same-key", different, "EQUITY", "USD", null).getRequestFingerprint());
        assertNotEquals(a.getId(), TradeOrder.proposal("new-key", request("1"), "EQUITY", "USD", null).getId());
    }

    @ParameterizedTest @ValueSource(strings = {"", "bad key", "\u0000", "中文"})
    void unsafeKeysRejected(String key) {
        assertThrows(ResponseStatusException.class, () -> TradeOrder.proposal(key, request("1"), "EQUITY", "USD", null));
    }

    @Test void incompleteAndUnconfirmedRequestsRejected() {
        TradeRequest r = request("1"); r.setConfirmed(false);
        assertThrows(ResponseStatusException.class, () -> TradeOrder.proposal("key", r, "EQUITY", "USD", null));
        r.setConfirmed(true); r.getInstrumentVerificationRequest().setInstrument(" ");
        assertThrows(ResponseStatusException.class, () -> TradeOrder.proposal("key", r, "EQUITY", "USD", null));
        assertThrows(ResponseStatusException.class, () -> TradeOrder.proposal("key", null, null, null, null));
        assertThrows(ResponseStatusException.class, () -> TradeOrder.proposal("x".repeat(129), request("1"), "EQUITY", "USD", null));
    }
}
