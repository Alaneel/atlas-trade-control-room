package com.gic.reliability;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Data
@Document("simulated_execution_receipts")
public class ExecutionReceipt {
    @Id private String id;
    private String requestFingerprint;
    private String counterparty;
    private String instrumentGroup;
    private String currency;
    private TradeStatus status;
    @Field(targetType = FieldType.DECIMAL128) private BigDecimal amount;
    private Instant createdAt;
}
