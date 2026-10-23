package com.gic.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Document(collection = "limits")
public class Limit {
    @Id
    private String id;
    private String instrumentGroup;
    private String counterparty;
    private String currency;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal availableLimit;
    private LocalDate dataDate;
}
