package com.gic.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import com.gic.validation.FinitePositive;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

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
    private Double amount;
    private boolean confirmed;
}
