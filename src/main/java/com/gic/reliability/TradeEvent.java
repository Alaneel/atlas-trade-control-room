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
    private String message;
    private Instant createdAt;

    public TradeEvent(TradeOrder trade) {
        id = trade.getId() + ":" + trade.getVersion();
        tradeId = trade.getId();
        sequence = trade.getVersion();
        status = trade.getStatus();
        message = trade.getMessage();
        createdAt = trade.getUpdatedAt();
    }
}
