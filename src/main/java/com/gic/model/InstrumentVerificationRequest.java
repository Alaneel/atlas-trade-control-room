package com.gic.model;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class InstrumentVerificationRequest {
    @NotBlank @Size(max = 100) private String instrumentGroup;
    @NotBlank @Size(max = 100) private String instrument;
    @NotBlank @Size(max = 3) private String settlementCurrency;
    @NotBlank @Size(max = 3) private String tradeCurrency;
    @NotBlank @Size(max = 100) private String country;
    @NotBlank @Size(max = 100) private String exchange;
    @NotBlank @Size(max = 100) private String department;
}
