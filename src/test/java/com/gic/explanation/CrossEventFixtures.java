package com.gic.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;

final class CrossEventFixtures {
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    static JsonNode rows() throws Exception { return JSON.readTree(Path.of("evals/explanations/cross-event-cases.json").toFile()).path("cases"); }
    static ExplanationSnapshot get(String id) throws Exception {
        for (JsonNode row : rows()) if (id.equals(row.path("id").asText())) return JSON.treeToValue(row.path("snapshot"), ExplanationSnapshot.class);
        throw new IllegalArgumentException("Unknown frozen cross-event fixture");
    }
}
