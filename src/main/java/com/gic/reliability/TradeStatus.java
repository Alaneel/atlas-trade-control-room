package com.gic.reliability;

public enum TradeStatus {
    RESERVED, SUBMITTED, UNKNOWN, EXECUTED, REJECTED;

    public boolean terminal() {
        return this == EXECUTED || this == REJECTED;
    }
}
