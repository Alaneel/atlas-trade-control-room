package com.gic.reliability;

import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** A durable local venue simulator, not a real exchange adapter. */
@Component
public class MongoExecutionGateway implements ExecutionGateway {
    public enum Scenario { SUCCESS, REJECT, TIMEOUT_AFTER_SUCCESS, TIMEOUT_BEFORE_SUBMIT }
    private final MongoTemplate mongo;
    private final Scenario scenario;

    public MongoExecutionGateway(MongoTemplate mongo, @Value("${atlas.execution.scenario:SUCCESS}") String scenario) {
        this.mongo = mongo;
        this.scenario = Scenario.valueOf(scenario);
    }

    @Override
    public ExecutionReceipt submit(TradeOrder trade) {
        Optional<ExecutionReceipt> previous = query(trade.getId());
        if (previous.isPresent()) { return verified(previous.get(), trade); }
        if (scenario == Scenario.TIMEOUT_BEFORE_SUBMIT) {
            throw new ExecutionUncertainException("Simulated timeout before a venue receipt exists");
        }
        ExecutionReceipt receipt = new ExecutionReceipt();
        receipt.setId(trade.getId());
        receipt.setRequestFingerprint(trade.getRequestFingerprint());
        receipt.setCounterparty(trade.getCounterparty());
        receipt.setInstrumentGroup(trade.getInstrumentGroup());
        receipt.setCurrency(trade.getCurrency());
        receipt.setAmount(trade.getAmount());
        receipt.setStatus(scenario == Scenario.REJECT ? TradeStatus.REJECTED : TradeStatus.EXECUTED);
        receipt.setCreatedAt(Instant.now());
        try { mongo.insert(receipt); }
        catch (DuplicateKeyException raced) { return verified(query(trade.getId()).orElseThrow(() -> raced), trade); }
        if (scenario == Scenario.TIMEOUT_AFTER_SUCCESS) {
            throw new ExecutionUncertainException("Simulated venue execution with lost acknowledgement");
        }
        return receipt;
    }

    private ExecutionReceipt verified(ExecutionReceipt receipt, TradeOrder trade) {
        if (receipt.getAmount().compareTo(trade.getAmount()) != 0
                || !java.util.Objects.equals(receipt.getRequestFingerprint(), trade.getRequestFingerprint())) {
            throw new IllegalStateException("Venue idempotency conflict");
        }
        return receipt;
    }

    @Override
    public Optional<ExecutionReceipt> query(String id) {
        return Optional.ofNullable(mongo.findById(id, ExecutionReceipt.class));
    }
}
