package com.gic.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentBloomFilterTest {
    @Test
    void addedInstrumentCanBeFoundAndClearRemovesIt() {
        InstrumentBloomFilter filter = new InstrumentBloomFilter();
        assertFalse(filter.mightContain("SGD-TEST-123"));
        filter.add("SGD-TEST-123");
        assertTrue(filter.mightContain("SGD-TEST-123"));
        filter.clear();
        assertFalse(filter.mightContain("SGD-TEST-123"));
    }
}
