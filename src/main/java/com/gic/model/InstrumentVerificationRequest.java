package com.gic.model;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class InstrumentVerificationRequest {
    @NotBlank private String instrumentGroup;
    @NotBlank private String instrument;
    @NotBlank private String settlementCurrency;
    @NotBlank private String tradeCurrency;
    @NotBlank private String country;
    @NotBlank private String exchange;
    @NotBlank private String department;
}
