package com.gic.reliability;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gic.model.InstrumentVerificationRequest;
import com.gic.model.TradeRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The durable RESERVED record is the outbox; it exists before external submission. */
@Data
@Document("reliable_trades")
public class TradeOrder {
    @Id private String id;
    @JsonIgnore private String requestFingerprint;
    private InstrumentVerificationRequest instrumentVerificationRequest;
    private String counterparty;
    private String instrumentGroup;
    private String currency;
    @Field(targetType = FieldType.DECIMAL128) private BigDecimal amount;
    private TradeStatus status;
    private DecisionCode decisionCode;
    private String ruleVersion;
    @Field(targetType = FieldType.DECIMAL128) private BigDecimal availableAtDecision;
    private String message;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;
    @JsonIgnore private String leaseToken;
    @JsonIgnore private Instant leaseUntil;
    private Instant nextAttemptAt;
    private int attempts;

    public static TradeOrder proposal(String key, TradeRequest request, String group,
                                      String currency, String rejectionReason) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-128 ASCII letters, digits or ._:-");
        }
        if (request == null || request.getInstrumentVerificationRequest() == null || !request.isConfirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A complete, explicitly confirmed trade is required");
        }
        InstrumentVerificationRequest i = request.getInstrumentVerificationRequest();
        for (String field : Arrays.asList(request.getCounterparty(), i.getInstrumentGroup(), i.getInstrument(),
                i.getTradeCurrency(), i.getSettlementCurrency(), i.getCountry(), i.getExchange(), i.getDepartment())) {
            if (field == null || field.isBlank() || field.length() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every instrument field and counterparty must be nonblank and at most 100 characters");
            }
        }
        BigDecimal amount = Money.amount(request.getAmount());
        TradeOrder order = new TradeOrder();
        order.id = hash(key);
        try {
            order.requestFingerprint = hash(new ObjectMapper().writeValueAsString(Arrays.asList(
                request.getCounterparty(), amount.toPlainString(), i.getInstrumentGroup(), i.getInstrument(),
                i.getTradeCurrency(), i.getSettlementCurrency(), i.getCountry(), i.getExchange(), i.getDepartment(), true)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot fingerprint trade", ex);
        }
        order.instrumentVerificationRequest = i;
        order.counterparty = request.getCounterparty();
        order.instrumentGroup = group;
        order.currency = currency;
        order.amount = amount;
        order.status = rejectionReason == null ? TradeStatus.RESERVED : TradeStatus.REJECTED;
        order.ruleVersion = DecisionCode.RULE_VERSION;
        order.decisionCode = rejectionReason == null ? DecisionCode.CAPACITY_RESERVED : DecisionCode.LEGACY_UNSPECIFIED;
        order.message = rejectionReason == null ? "Capacity reserved; awaiting submission" : rejectionReason;
        order.createdAt = Instant.now();
        order.updatedAt = order.createdAt;
        order.nextAttemptAt = order.createdAt;
        order.leaseUntil = Instant.EPOCH;
        return order;
    }

    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) { result.append(String.format("%02x", b & 0xff)); }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
