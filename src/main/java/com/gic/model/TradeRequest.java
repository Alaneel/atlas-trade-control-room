package com.gic.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import com.gic.validation.FinitePositive;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeRequest {
    @Valid
    @NotNull(message = "Instrument verification request is required")
    private InstrumentVerificationRequest instrumentVerificationRequest;

    @NotBlank(message = "Counterparty is required")
    private String counterparty;

    @NotNull(message = "Amount is required")
    @FinitePositive
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal amount;
    private boolean confirmed;
}
