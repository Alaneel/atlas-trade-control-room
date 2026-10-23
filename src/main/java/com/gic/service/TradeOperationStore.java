package com.gic.service;

import com.gic.exception.BusinessException;
import com.gic.exception.IdempotencyConflictException;
import com.gic.model.InstrumentVerificationRequest;
import com.gic.model.TradeOperation;
import com.gic.model.TradeRequest;
import com.gic.model.TradeResult;
import com.gic.validation.MoneyAmounts;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/** An _id insert is the sole permission to invoke the trade decision path. */
@Service
public class TradeOperationStore {
    private final MongoTemplate mongo;

    public TradeOperationStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Claim acquire(String suppliedKey, TradeRequest request) {
        String key = suppliedKey == null ? UUID.randomUUID().toString() : suppliedKey;
        if (!key.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain 1 to 128 ASCII letters, digits, dots, underscores, or hyphens");
        }
        String fingerprint = fingerprint(request);
        TradeOperation operation = new TradeOperation();
        operation.setId(sha256("atlas-trade:v1:" + key));
        operation.setFingerprint(fingerprint);
        operation.setStatus("IN_PROGRESS");
        operation.setRequestSnapshot(request);
        operation.setCreatedAt(Instant.now());
        operation.setUpdatedAt(operation.getCreatedAt());
        try {
            mongo.insert(operation);
            return new Claim(true, operation);
        } catch (DuplicateKeyException duplicate) {
            TradeOperation existing = mongo.findById(operation.getId(), TradeOperation.class);
            if (existing == null) {
                // An external deletion is not permission to execute again.
                throw new IllegalStateException("Trade operation disappeared during duplicate admission", duplicate);
            }
            if (!fingerprint.equals(existing.getFingerprint())) {
                throw new IdempotencyConflictException();
            }
            return new Claim(false, existing);
        }
    }

    public void complete(String id, TradeResult result) {
        long matched = mongo.updateFirst(pending(id), new Update()
                .set("status", "COMPLETED")
                .set("resultStatus", result.getStatus())
                .set("resultMessage", result.getMessage())
                .set("updatedAt", Instant.now()), TradeOperation.class).getMatchedCount();
        if (matched != 1) {
            throw new IllegalStateException("Trade completion could not be recorded");
        }
    }

    public void requireReconciliation(String id) {
        mongo.updateFirst(pending(id), new Update()
                .set("status", "RECONCILIATION_REQUIRED")
                .set("updatedAt", Instant.now()), TradeOperation.class);
    }

    private Query pending(String id) {
        return Query.query(Criteria.where("_id").is(id).and("status").is("IN_PROGRESS"));
    }

    private String fingerprint(TradeRequest request) {
        InstrumentVerificationRequest instrument = request.getInstrumentVerificationRequest();
        MessageDigest digest = newDigest();
        // Length-prefix every UTF-8 field. Global JSON formatting, escaping,
        // locale, and attribute delimiters cannot change the request identity.
        field(digest, "atlas-payload-v1");
        field(digest, request.getCounterparty());
        field(digest, MoneyAmounts.requirePositive(request.getAmount()).toPlainString());
        field(digest, Boolean.toString(request.isConfirmed()));
        field(digest, instrument.getInstrumentGroup());
        field(digest, instrument.getInstrument());
        field(digest, instrument.getDepartment());
        field(digest, instrument.getCountry());
        field(digest, instrument.getExchange());
        field(digest, instrument.getTradeCurrency());
        field(digest, instrument.getSettlementCurrency());
        return hex(digest.digest());
    }

    private void field(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    private String sha256(String value) {
        return hex(newDigest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(64);
        for (byte b : bytes) {
            result.append(Character.forDigit((b >>> 4) & 0xf, 16));
            result.append(Character.forDigit(b & 0xf, 16));
        }
        return result.toString();
    }

    public static final class Claim {
        private final boolean owner;
        private final TradeOperation operation;

        public Claim(boolean owner, TradeOperation operation) {
            this.owner = owner;
            this.operation = operation;
        }

        public boolean isOwner() { return owner; }
        public TradeOperation getOperation() { return operation; }
    }
}
