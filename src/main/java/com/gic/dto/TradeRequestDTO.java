package com.gic.dto;

import com.gic.model.InstrumentVerificationRequest;

import javax.validation.Valid;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import com.gic.validation.FinitePositive;

public class TradeRequestDTO {

    @Valid
    @NotNull(message = "Instrument verification request is required")
    private InstrumentVerificationRequest instrumentVerificationRequest;

    @NotBlank(message = "Counterparty is required")
    @Size(min = 2, max = 100, message = "Counterparty must be between 2 and 100 characters")
    private String counterparty;

    @NotNull(message = "Amount is required")
    @FinitePositive
    private BigDecimal amount;

    // Constructors
    public TradeRequestDTO() {}

    public TradeRequestDTO(InstrumentVerificationRequest instrumentVerificationRequest,
                          String counterparty, BigDecimal amount) {
        this.instrumentVerificationRequest = instrumentVerificationRequest;
        this.counterparty = counterparty;
        this.amount = amount;
    }

    // Getters and Setters
    public InstrumentVerificationRequest getInstrumentVerificationRequest() {
        return instrumentVerificationRequest;
    }

    public void setInstrumentVerificationRequest(InstrumentVerificationRequest instrumentVerificationRequest) {
        this.instrumentVerificationRequest = instrumentVerificationRequest;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(String counterparty) {
        this.counterparty = counterparty;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
