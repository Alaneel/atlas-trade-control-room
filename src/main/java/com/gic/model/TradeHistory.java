package com.gic.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Document(collection = "trade_history")
public class TradeHistory {
    @Id
    private String id;
    private InstrumentVerificationRequest instrumentVerificationRequest;
    private String counterparty;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;
    private LocalDateTime timestamp;
    private String status;
}
