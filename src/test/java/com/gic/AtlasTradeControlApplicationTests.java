package com.gic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "ATLAS_MONGO_URI", matches = ".+")
@SpringBootTest
@ActiveProfiles("test")
class AtlasTradeControlApplicationTests {

    @Test
    void contextLoads() {
        // Test that the application context loads successfully
    }
}
