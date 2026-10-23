package com.gic.reliability.integration;

import com.gic.reliability.ReliableTradeStore;
import com.gic.reliability.TransactionRunner;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks the unconditional raw-BSON startup gate independently of the development seed. */
@EnabledIfEnvironmentVariable(named = "ATLAS_MONGO_URI", matches = ".+")
class LedgerSchemaMongoTest {
    private static MongoAcceptanceSupport fixture;

    @BeforeAll
    static void connect() {
        fixture = new MongoAcceptanceSupport();
    }

    @AfterAll
    static void cleanupOnlyOwnedDatabase() {
        if (fixture != null) fixture.close();
    }

    @ParameterizedTest(name = "startup refuses {0} ledger")
    @ValueSource(strings = {"double", "missing", "null", "negative", "fractional_cent", "invariant_mismatch"})
    void startupRejectsMalformedBalancesWithoutInferringOrMigratingThem(String corruption) {
        String id = UUID.randomUUID().toString();
        Document account = validAccount(id);
        switch (corruption) {
            case "double":
                account.put("availableLimit", 100.0);
                break;
            case "missing":
                account.remove("reservedLimit");
                break;
            case "null":
                account.put("reservedLimit", null);
                break;
            case "negative":
                account.put("availableLimit", decimal("101.00"));
                account.put("usedLimit", decimal("-1.00"));
                break;
            case "fractional_cent":
                account.put("availableLimit", decimal("99.999"));
                account.put("usedLimit", decimal("0.001"));
                break;
            case "invariant_mismatch":
                account.put("availableLimit", decimal("99.00"));
                break;
            default:
                throw new AssertionError("Unknown corruption case");
        }
        fixture.mongo.getCollection("limits").insertOne(account);
        try {
            ReliableTradeStore store = new ReliableTradeStore(fixture.mongo, new TransactionRunner(fixture.transactions));
            assertThatThrownBy(store::initialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid or legacy ledger");
            // Positive control: removing the bad input, rather than inferring its balances, restores startup.
            fixture.mongo.getCollection("limits").replaceOne(new Document("_id", id), validAccount(id));
            store.initialize();
        } finally {
            fixture.mongo.getCollection("limits").deleteOne(new Document("_id", id));
        }
    }

    private static Document validAccount(String id) {
        return new Document("_id", id).append("counterparty", "schema-" + id)
                .append("instrumentGroup", "EQUITY").append("currency", "USD")
                .append("totalLimit", decimal("100.00"))
                .append("availableLimit", decimal("100.00"))
                .append("reservedLimit", decimal("0.00"))
                .append("usedLimit", decimal("0.00"));
    }

    private static Decimal128 decimal(String amount) {
        return new Decimal128(new BigDecimal(amount));
    }
}
