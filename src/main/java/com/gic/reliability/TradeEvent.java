package com.gic.reliability;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data @NoArgsConstructor
@Document("trade_events")
public class TradeEvent {
    @Id private String id;
    private String tradeId;
    private long sequence;
    private TradeStatus status;
    private DecisionCode decisionCode;
    private String ruleVersion;
    @org.springframework.data.mongodb.core.mapping.Field(targetType = org.springframework.data.mongodb.core.mapping.FieldType.DECIMAL128)
    private java.math.BigDecimal availableAtDecision;
    private String message;
    private Instant createdAt;

    public TradeEvent(TradeOrder trade) {
        id = trade.getId() + ":" + trade.getVersion();
        tradeId = trade.getId();
        sequence = trade.getVersion();
        status = trade.getStatus();
        decisionCode = trade.getDecisionCode();
        ruleVersion = trade.getRuleVersion();
        availableAtDecision = trade.getAvailableAtDecision();
        message = trade.getMessage();
        createdAt = trade.getUpdatedAt();
    }
}
