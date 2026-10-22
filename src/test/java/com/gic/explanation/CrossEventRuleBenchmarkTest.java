package com.gic.explanation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Opt-in pure-selector microbenchmark; no database/provider calls and no timing-based pass/fail. */
@EnabledIfSystemProperty(named = "atlas.explanation.rules-benchmark", matches = "true")
class CrossEventRuleBenchmarkTest {
    @Test void compareMaxBoundedHistoryWithRetainedPairEnumeration() throws Exception {
        long[] offsets = new long[64]; for (int i = 0; i < offsets.length; i++) offsets[i] = i * 1000L;
        var a = new ExplanationEngine(new RuleCatalog()).analyze(CrossEventBoundaryFixtures.snapshot("benchmark_64", offsets, null, null));
        var expected = CrossEventRuleOracle.enumerate(a);
        for (int i = 0; i < 3; i++) { CrossEventRuleOracle.enumerate(a); CrossEventVerifier.strongBaseline(a); }
        List<Long> original = new ArrayList<>(), current = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            long start = System.nanoTime(); var before = CrossEventRuleOracle.enumerate(a); original.add(System.nanoTime() - start);
            start = System.nanoTime(); var after = CrossEventVerifier.strongBaseline(a); current.add(System.nanoTime() - start);
            assertThat(before).isEqualTo(expected); assertThat(after).isEqualTo(expected);
        }
        var sortedOld = original.stream().sorted().toList(); var sortedNew = current.stream().sorted().toList();
        long oldMedian = sortedOld.get(5), newMedian = sortedNew.get(5);
        Files.createDirectories(Path.of("target"));
        var report = new java.util.LinkedHashMap<String, Object>(Map.of(
            "kind", "local_pure_rule_selector_microbenchmark", "event_count", 64, "warmups_per_variant", 3,
            "measured_iterations_per_variant", 11, "pair_enumeration_ns", original, "linear_selection_ns", current,
            "median_pair_ns", oldMedian, "median_linear_ns", newMedian, "median_ratio", (double) oldMedian / newMedian,
            "scope", "One development fixture, sequential old-then-new, warmed in one local JVM under the same current proof/rendering kernel. "
                + "No database/HTTP/model requests. Exact output equality is asserted; timings are descriptive only, not an SLA or production load result."));
        report.put("unmeasured_setup_calls", Map.of("pair_enumeration", 1, "linear_selection", 0));
        report.put("environment", Map.of("java", System.getProperty("java.version"), "vendor", System.getProperty("java.vendor"),
            "os", System.getProperty("os.name"), "architecture", System.getProperty("os.arch"), "coverage_agent", "Default Maven test instrumentation"));
        CrossEventFixtures.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/atlas-rule-selector-benchmark.json").toFile(), report);
    }
}
