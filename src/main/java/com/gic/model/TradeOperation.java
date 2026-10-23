package com.gic.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Durable admission record. No TTL: removing a record would permit replay. */
@Data
@Document(collection = "trade_operations")
public class TradeOperation {
    @Id
    private String id;
    private String fingerprint;
    private String status;
    private TradeRequest requestSnapshot;
    private String resultStatus;
    private String resultMessage;
    private Instant createdAt;
    private Instant updatedAt;
}
