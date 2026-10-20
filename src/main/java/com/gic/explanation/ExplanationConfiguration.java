package com.gic.explanation;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExplanationConfiguration {
    @Bean public RuleCatalog explanationRules() { return new RuleCatalog(); }
    @Bean public ExplanationEngine explanationEngine(RuleCatalog rules) { return new ExplanationEngine(rules); }
    @Bean public ModelPlanner explanationPlanner(@Value("${atlas.explanation.enabled:false}") boolean enabled,
            @Value("${atlas.explanation.api-key:}") String key, @Value("${atlas.explanation.model:}") String model,
            @Value("${atlas.explanation.timeout-ms:8000}") long timeout) {
        if (!enabled) return analysis -> { throw new ModelPlanner.Failure("disabled", false); };
        return new OpenAiPlanner(key, model, Duration.ofMillis(timeout));
    }
}
