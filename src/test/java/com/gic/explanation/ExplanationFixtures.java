package com.gic.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;

final class ExplanationFixtures {
    static ExplanationSnapshot get(String id) {
        try {
            var json = new ObjectMapper().registerModule(new JavaTimeModule());
            for (var row : json.readTree(Path.of("evals/explanations/cases.json").toFile()).path("cases")) {
                if (id.equals(row.path("id").asText())) return json.treeToValue(row.path("snapshot"), ExplanationSnapshot.class);
            }
            throw new IllegalArgumentException(id);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
