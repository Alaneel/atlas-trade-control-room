package com.gic.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeResult {
    private String status;
    private String message;
    private String tradeId;

    public TradeResult(String status, String message) {
        this(status, message, null);
    }
}
