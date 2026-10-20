#!/usr/bin/env python3
"""Explicitly opted-in cross-event generation and two-position three-way review; <=30 requests."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
import signal
from pathlib import Path
import subprocess
import sys
import uuid
from cross_event_report import summarize, markdown

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("atlas_live_config", ROOT / "scripts/run-live-explanation-eval.py")
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, choices=range(1, 11), default=10)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.local")
    parser.add_argument("--check-config", action="store_true")
    args = parser.parse_args()
    try:
        values = config.configuration(args.env_file)
    except (ValueError, OSError):
        print("Configure private .env.local with the documented key/model; values are never printed.", file=sys.stderr)
        return 2
    print(json.dumps({"api_key_configured": True, "model": values["OPENAI_MODEL"], "generation_limit": args.limit,
                      "review_limit": 2 * args.limit, "request_cap": 3 * args.limit, "automatic_retries": 0}), flush=True)
    if args.check_config:
        return 0
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    destination = ROOT / "target/cross-event-evaluations" / run_id
    destination.mkdir(parents=True, exist_ok=False)
    output = destination / "results.json"
    paths = sorted({*ROOT.glob("src/main/java/com/gic/explanation/*.java"), *ROOT.glob("src/test/java/com/gic/explanation/*.java"),
                    *ROOT.glob("scripts/*cross*event*.py"), ROOT / "scripts/run-live-explanation-eval.py",
                    ROOT / "evals/explanations/cross-event-cases.json", ROOT / "evals/explanations/pricing.json",
                    ROOT / "src/main/resources/explanations/rules-v1.json", ROOT / "pom.xml"})
    manifest = {"run_id": run_id, "source_commit": config.git("rev-parse", "HEAD"), "working_tree_dirty": bool(config.git("status", "--porcelain")),
                "tracked_diff_sha256": hashlib.sha256(config.git("diff", "HEAD").encode()).hexdigest(),
                "model": values["OPENAI_MODEL"], "generation_limit": args.limit, "review_limit": 2 * args.limit,
                "request_cap": 3 * args.limit, "automatic_retries": 0, "timeout_ms": 8000, "max_output_tokens": 1400,
                "service_tier": "default", "source_sha256": {str(p.relative_to(ROOT)): config.digest(p) for p in paths}}
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    environment = os.environ.copy()
    environment.update(values)
    command = ["mvn", "-o", "-q", "test", "-Dtest=CrossEventLiveEvaluationTest", "-Datlas.explanation.cross-event.live=true",
               f"-Datlas.explanation.limit={args.limit}", f"-Datlas.explanation.report={output}"]
    child = subprocess.Popen(command, cwd=ROOT, env=environment, text=True, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, start_new_session=True)
    try:
        log, _ = child.communicate(timeout=600)
        code = child.returncode
    except subprocess.TimeoutExpired:
        os.killpg(child.pid, signal.SIGTERM)
        try:
            log, _ = child.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            log, _ = child.communicate()
        log += "\nRunner timed out. Checkpoint contains only observed responses; in-flight billing may be unknown.\n"
        code = 124
    (destination / "runner.log").write_text(log.replace(values["OPENAI_API_KEY"], "[REDACTED]"))
    manifest["runner_exit_code"] = code
    if output.exists():
        report = json.loads(output.read_text())
        summary = summarize(report)
        (destination / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        (destination / "report.md").write_text(markdown(report, summary))
        manifest["results_sha256"] = config.digest(output)
        print(json.dumps({"report_directory": str(destination), "summary": summary}, indent=2), flush=True)
    else:
        print("No result file; inspect sanitized runner.log.", file=sys.stderr)
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return code


if __name__ == "__main__":
    sys.exit(main())
