package com.gic.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import lombok.Data;

import java.time.LocalDate;
import java.math.BigDecimal;

@Data
@Document(collection = "limits")
@CompoundIndex(name = "limit_scope", def = "{'counterparty':1,'instrumentGroup':1,'currency':1}", unique = true)
public class Limit {
    @Id
    private String id;
    private String instrumentGroup;
    private String counterparty;
    private String currency;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal availableLimit;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal reservedLimit;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal usedLimit;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal totalLimit;
    private LocalDate dataDate;
}
