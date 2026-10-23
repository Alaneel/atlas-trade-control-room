#!/usr/bin/env python3
"""Load local credentials without shell evaluation; archive each bounded real-provider run."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {"OPENAI_API_KEY", "OPENAI_MODEL", "ATLAS_AI_EXPLANATIONS_ENABLED"}


def configuration(path):
    values = {key: os.environ.get(key, "") for key in ALLOWED}
    if path.exists():
        if os.name == "posix" and stat.S_IMODE(path.stat().st_mode) & 0o077:
            raise ValueError("Local credential file must be private: chmod 600 .env.local")
        for number, line in enumerate(path.read_text().splitlines(), 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, separator, value = line.removeprefix("export ").partition("=")
            name, value = name.strip(), value.strip()
            if not separator or name not in ALLOWED:
                raise ValueError(f"Unsupported configuration at line {number}; only the three documented variables are accepted")
            if value[:1] in {"'", '"'}:
                if len(value) < 2 or value[-1] != value[0]:
                    raise ValueError(f"Unmatched quote at configuration line {number}")
                value = value[1:-1]
            values[name] = value
    if not values["OPENAI_API_KEY"].strip():
        raise ValueError("OPENAI_API_KEY is empty; configure it locally, never in chat")
    model = values["OPENAI_MODEL"]
    if not re.fullmatch(r"[A-Za-z0-9._:-]{1,100}", model) or model.startswith("sk-"):
        raise ValueError("OPENAI_MODEL must be an available Structured Outputs model ID")
    return values


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def markdown(report):
    summary = report["summary"]
    cost = summary["cost"]
    latency = summary["latency_ms"]
    lines = ["# Atlas real-model explanation evaluation", "",
             f"Recorded: {report['recorded_at']} · Requested model: `{report['model']}`", "",
             f"Cases: {summary['cases']} · Initiated requests: {summary['initiated_requests']} · Validated plans: {summary['validated_plans']}", "",
             "| Metric | Result |", "| --- | --- |",
             f"| Fallback rate | {summary['fallback_rate']:.1%} ({summary['fallbacks']}/{summary['cases']}) |",
             f"| End-to-end latency P50 / P95 / max | {latency['p50']} / {latency['p95']} / {latency['max']} ms |",
             f"| Reported input / cached input / output tokens | {cost['reported_input_tokens']} / {cost['reported_cached_input_tokens']} / {cost['reported_output_tokens']} |",
             f"| Estimated total USD | {cost['estimated_total_usd'] if cost['estimated_total_usd'] is not None else 'Unknown: incomplete usage/pricing'} |",
             f"| Known priced usage USD range | {cost['known_usage_estimate_usd_min']}–{cost['known_usage_estimate_usd_max']} |",
             f"| Requests missing usage / unpriced | {cost['requests_missing_usage']} / {cost['requests_unpriced']} |", "",
             "Cost is a token-usage estimate at the recorded standard price, not an invoice. A timed-out request can still be billable; missing usage is not zero cost.", "",
             "## Mechanical quality checks", "",
             "| Check | Final application output (includes fallback) | Accepted model plans only |", "| --- | --- | --- |"]
    quality = summary["quality"]
    for name, score in quality["final_application_output"].items():
        model_score = quality["accepted_model_output"][name]
        lines.append(f"| {name} | {score['passed']}/{score['total']} | {model_score['passed']}/{model_score['total']} |")
    controls = quality["abstention_controls"]
    lines += ["", f"Invalid-evidence abstention controls: {controls['passed']}/{controls['total']}; paid requests: {quality['abstention_control_requests']}.",
              f"Presentation identical to rule baseline: {quality['unchanged_presentation_vs_rules']}/{summary['cases']}.", "",
              quality["scope"], "", "## Per-case results", "",
              "| Case | Mode | Outcome | Latency ms |", "| --- | --- | --- |"]
    for row in report["results"]:
        actual = row["explanation"]
        lines.append(f"| {row['caseId']} | {actual['mode']} | {actual['providerOutcome']} | {actual['elapsedMillis']} |")
    lines += ["", "## Rules and real-provider presentation", ""]
    for row in report["results"]:
        lines += [f"### {row['caseId']}", "", f"Summary: {row['explanation']['summary']}", "",
                  "Rule finding order: " + ", ".join(f["id"] for f in row["rules"]["findings"]), "",
                  "Application finding order: " + ", ".join(f["id"] for f in row["explanation"]["findings"]), ""]
        for finding in row["explanation"]["findings"]:
            lines.append(f"- {finding['text']}")
        lines.append("")
    lines += [f"[Pricing source]({cost['pricing_source']}), verified {cost['pricing_verified_on']}.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.local")
    parser.add_argument("--limit", type=int, choices=range(1, 13), default=6)
    parser.add_argument("--check-config", action="store_true", help="Presence/format check only; no API call")
    args = parser.parse_args()
    try:
        values = configuration(args.env_file)
    except (ValueError, OSError):
        # File contents and parser values may include secrets. Keep errors generic.
        print("Configuration is missing or invalid. Use a private (chmod 600) .env.local with OPENAI_API_KEY and OPENAI_MODEL; values are never printed.", file=sys.stderr)
        return 2
    print(json.dumps({"api_key_configured": True, "model": values["OPENAI_MODEL"], "request_limit": args.limit, "automatic_retries": 0}))
    if args.check_config:
        return 0
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    destination = ROOT / "target" / "model-evaluations" / run_id
    destination.mkdir(parents=True, exist_ok=False)
    report_path = destination / "results.json"
    paths = sorted({*ROOT.glob("src/main/java/com/gic/explanation/*.java"), *ROOT.glob("src/test/java/com/gic/explanation/*.java"),
                    ROOT / "evals/explanations/cases.json", ROOT / "evals/explanations/pricing.json",
                    ROOT / "src/main/resources/explanations/rules-v1.json", Path(__file__).resolve(), ROOT / "pom.xml"})
    manifest = {"run_id": run_id, "source_commit": git("rev-parse", "HEAD"), "working_tree_dirty": bool(git("status", "--porcelain")),
                "tracked_diff_sha256": hashlib.sha256(git("diff", "HEAD").encode()).hexdigest(),
                "request_limit": args.limit, "model": values["OPENAI_MODEL"], "automatic_retries": 0,
                "timeout_ms": 8000, "max_output_tokens": 1400, "service_tier": "default",
                "source_sha256": {str(path.relative_to(ROOT)): digest(path) for path in paths}}
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    environment = os.environ.copy()
    environment.update(values)
    # Explicitly opted in only for this one test process; ordinary CI remains offline.
    command = ["mvn", "-o", "-q", "test", "-Dtest=LiveExplanationModelEvaluationTest",
               "-Datlas.explanation.live=true", f"-Datlas.explanation.limit={args.limit}", f"-Datlas.explanation.report={report_path}"]
    completed = subprocess.run(command, cwd=ROOT, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
    sanitized = completed.stdout.replace(values["OPENAI_API_KEY"], "[REDACTED]")
    (destination / "runner.log").write_text(sanitized)
    if report_path.exists():
        report = json.loads(report_path.read_text())
        (destination / "report.md").write_text(markdown(report))
        manifest["results_sha256"] = digest(report_path)
        manifest["runner_exit_code"] = completed.returncode
        (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(json.dumps({"report_directory": str(destination), "summary": report["summary"]}, indent=2))
    else:
        print("No evaluation report was produced; inspect the sanitized runner log.", file=sys.stderr)
    return completed.returncode


if __name__ == "__main__":
    sys.exit(main())
