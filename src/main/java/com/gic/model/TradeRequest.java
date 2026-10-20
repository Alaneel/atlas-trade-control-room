package com.gic.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeRequest {
    @Valid @NotNull
    private InstrumentVerificationRequest instrumentVerificationRequest;
    @NotBlank @Size(max = 100)
    private String counterparty;
    @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2)
    private BigDecimal amount;
    @AssertTrue(message = "Trade must be explicitly confirmed")
    private boolean confirmed;
}
