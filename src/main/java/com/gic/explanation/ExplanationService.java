package com.gic.explanation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ExplanationService {
    private final ExplanationReader reader;
    private final ExplanationEngine engine;
    private final ModelPlanner planner;
    public ExplanationService(ExplanationReader reader, ExplanationEngine engine, ModelPlanner planner) {
        this.reader = reader; this.engine = engine; this.planner = planner;
    }
    public Explanation explain(String tradeId, boolean useModel) {
        long start = System.nanoTime();
        ExplanationSnapshot snapshot = reader.load(tradeId);
        ExplanationEngine.Analysis analysis = engine.analyze(snapshot);
        ExplanationPlan plan = ExplanationPlan.baseline(analysis);
        String mode = "rules";
        String outcome = useModel ? "evidence_abstention" : "not_requested";
        String model = null;
        int calls = 0;
        Long input = null, output = null;
        if (useModel && !analysis.abstained()) {
            mode = "rules_fallback";
            try {
                ModelPlanner.Reply reply = planner.propose(analysis);
                calls = reply.modelRequest() ? 1 : 0;
                model = reply.model(); input = reply.inputTokens(); output = reply.outputTokens();
                plan = ExplanationPlan.parseAndValidate(reply.json(), analysis);
                mode = reply.modelRequest() ? "model_ordered" : "fixture_ordered";
                outcome = "validated";
            } catch (ModelPlanner.Failure ex) {
                if (ex.modelRequest()) calls = 1;
                if (ex.usage() != null) {
                    model = ex.usage().requestedModel(); input = ex.usage().inputTokens(); output = ex.usage().outputTokens();
                }
                outcome = ex.outcome(); plan = ExplanationPlan.baseline(analysis);
            } catch (RuntimeException ex) {
                outcome = "provider_error"; plan = ExplanationPlan.baseline(analysis);
            }
            // A slow model may finish after the trade changes. Never publish its obsolete plan.
            ExplanationSnapshot fresh = reader.load(tradeId);
            ExplanationEngine.Analysis current = engine.analyze(fresh);
            if (!analysis.snapshotHash().equals(current.snapshotHash())) {
                snapshot = fresh; analysis = current; plan = ExplanationPlan.baseline(current);
                mode = "rules_fallback"; outcome = "snapshot_changed";
            }
        }
        Map<String, Explanation.Finding> findings = analysis.findings().stream()
            .collect(Collectors.toMap(Explanation.Finding::id, Function.identity()));
        Map<String, Explanation.Check> checks = analysis.checks().stream()
            .collect(Collectors.toMap(Explanation.Check::id, Function.identity()));
        List<Explanation.Finding> ordered = plan.findingIds().stream().map(findings::get).toList();
        return new Explanation(snapshot.tradeId(), snapshot.version(), String.valueOf(snapshot.status()), analysis.snapshotHash(),
            snapshot.ruleVersion(), OpenAiPlanner.PROMPT_VERSION, mode, outcome, model, calls, input, output,
            (System.nanoTime() - start) / 1_000_000, analysis.abstained(), findings.get(plan.headlineId()).text(),
            ordered, plan.checkIds().stream().map(checks::get).toList(), analysis.evidence(), analysis.limitations(), Instant.now());
    }
}
