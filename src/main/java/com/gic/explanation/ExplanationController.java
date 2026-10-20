package com.gic.explanation;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/trader/trades")
public class ExplanationController {
    private final ExplanationService explanations;
    public ExplanationController(ExplanationService explanations) { this.explanations = explanations; }
    @GetMapping("/{id}/explanation")
    public ResponseEntity<Explanation> explain(@PathVariable String id, @RequestParam(defaultValue = "rules") String mode) {
        if ("rules_plus".equals(mode)) return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(explanations.explainCrossEventRules(id));
        if (!"rules".equals(mode) && !"ai".equals(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be rules, rules_plus or ai");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(explanations.explain(id, "ai".equals(mode)));
    }
}
