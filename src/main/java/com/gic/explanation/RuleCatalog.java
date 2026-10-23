package com.gic.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gic.reliability.DecisionCode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reviewed policy text, versioned with deterministic decision codes. */
public final class RuleCatalog {
    private final String version;
    private final Map<String, String> rules;
    public RuleCatalog() {
        try (var input = RuleCatalog.class.getResourceAsStream("/explanations/rules-v1.json")) {
            if (input == null) throw new IllegalStateException("Missing explanation rule catalog");
            JsonNode root = new ObjectMapper().readTree(input);
            version = root.path("version").asText();
            if (!DecisionCode.RULE_VERSION.equals(version)) throw new IllegalStateException("Rule version mismatch");
            Map<String, String> values = new LinkedHashMap<>();
            root.path("rules").fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
            rules = Map.copyOf(values);
        } catch (IOException ex) { throw new IllegalStateException("Invalid explanation rule catalog", ex); }
    }
    public String version() { return version; }
    public String text(String id) {
        String text = rules.get(id);
        if (text == null) throw new IllegalArgumentException("Unknown explanation rule");
        return text;
    }
    public Map<String, String> rules() { return rules; }
}
