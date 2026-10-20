#!/usr/bin/env python3
"""Offline presentation or bounded real-HTTP read-only explanation demonstration.

The local demo uses a dedicated labelled Mongo container and creates a UUID
fixture database. Provider calls are explicitly disabled. It does not claim
real-model quality. No container or pre-existing database is deleted here.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]


def offline(args):
    subprocess.run(["mvn", "-q", "-Dtest=ExplanationEvaluationTest", "test"], cwd=ROOT, check=True)
    report = json.loads((ROOT / "target/atlas-explanation-evaluation.json").read_text())
    selected = {"capacity_rejection", "unknown_success_receipt", "legacy_rejection"}
    for row in report["results"]:
        if row["id"] in selected:
            print(row["id"] + ": " + row["rules"]["summary"])
    print(f"{report['cases']} synthetic cases; {report['scripted_adversarial_plans_rejected']} rejected scripted plans; real model calls=0")


def local(args, report):
    spec = importlib.util.spec_from_file_location("atlas_local_smoke", ROOT / "scripts/process-recovery-smoke.py")
    smoke = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(smoke)
    smoke.OWNED_CONTAINER = args.mongo_container
    smoke.OWNERSHIP_VALUE = args.owner_value
    smoke.owned_container()
    jar = args.jar.resolve()
    smoke.require(jar.is_file(), "Build the executable JAR before running this demo")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))
    database = "atlas_explain_" + uuid.uuid4().hex
    uri = f"mongodb://127.0.0.1:28027/{database}?replicaSet=atlas-rs&directConnection=true"
    base = f"http://127.0.0.1:{args.port}"
    # This demonstration measures deterministic explanation/fallback contracts, never paid calls.
    os.environ["ATLAS_AI_EXPLANATIONS_ENABLED"] = "false"
    report.update({"database": database, "database_cleanup": "retained for caller-owned container cleanup",
        "mongo_container": args.mongo_container, "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "source_revision": smoke.command("git", "rev-parse", "HEAD"),
        "working_tree_dirty": bool(smoke.command("git", "status", "--porcelain")),
        "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "mode": "real_http_rules_and_disabled_provider_fallback", "real_model_calls": 0, "cases": []})
    body = {"instrumentVerificationRequest": {"instrumentGroup": "EQUITY", "instrument": "AAPL",
        "department": "GLOBAL_MARKETS", "country": "US", "exchange": "NASDAQ", "tradeCurrency": "USD",
        "settlementCurrency": "USD"}, "counterparty": "NORTHSTAR", "amount": "10.00", "confirmed": True}

    def documents():
        # Generated name is the only inserted identifier. Never execute caller-supplied JavaScript.
        smoke.require(database.startswith("atlas_explain_") and len(database) == 46
            and all(c in "0123456789abcdef" for c in database[14:]), "Unexpected fixture database")
        expression = (f"const d=db.getSiblingDB({json.dumps(database)});"
            "const out={}; for (const n of ['limits','reliable_trades','trade_events','simulated_execution_receipts','instruments'])"
            "out[n]=d.getCollection(n).find({}).sort({_id:1}).toArray(); print(EJSON.stringify(out));")
        raw = smoke.command("docker", "exec", args.mongo_container, "mongosh", "--quiet", "--eval", expression)
        return hashlib.sha256(raw.encode()).hexdigest()

    def inspect(name, response, expected_status, expected_headline):
        code, trade = response
        smoke.require(code in (200, 202) and trade["status"] == expected_status, f"Unexpected trade result {trade}")
        trade_id = trade["tradeId"]
        before = documents()
        prefix = f"/api/trader/trades/{trade_id}"
        rule_status, rules = smoke.http(base, "GET", prefix + "/explanation")
        ai_status, fallback = smoke.http(base, "GET", prefix + "/explanation?mode=ai")
        strong_status, strong = smoke.http(base, "GET", prefix + "/explanation?mode=rules_plus")
        smoke.require(rule_status == ai_status == strong_status == 200, "Explanation endpoint failed")
        smoke.require(strong["mode"] == "rules_plus" and strong["modelCalls"] == 0, "Cross-event rules unexpectedly called a model")
        smoke.require(rules["mode"] == "rules" and fallback["mode"] == "rules_fallback", "Expected explicit fallback provenance")
        smoke.require(fallback["providerOutcome"] == "disabled" and fallback["modelCalls"] == 0, "Unexpected provider call")
        smoke.require(rules["tradeStatus"] == expected_status and rules["findings"][0]["id"] == expected_headline, "Wrong grounded explanation")
        smoke.require(all(check["id"].startswith("inspect.") for check in rules["nextChecks"]), "Unexpected action capability")
        post_status, _ = smoke.http(base, "POST", prefix + "/explanation")
        smoke.require(post_status == 405, "Explanation exposes a mutation route")
        after = documents()
        smoke.require(before == after, "Explanation requests modified business documents")
        _, limit = smoke.http(base, "GET", "/api/trader/limit/NORTHSTAR/EQUITY?currency=USD")
        smoke.require(sum(Decimal(str(limit[key])) for key in ['availableLimit', 'reservedLimit', 'usedLimit'])
            == Decimal(str(limit['totalLimit'])), "Ledger conservation failed")
        report["cases"].append({"id": name, "trade_response": trade, "rules": rules, "strong_rules": strong, "ai_fallback": fallback,
            "mutation_route_status": post_status, "documents_before_sha256": before, "documents_after_sha256": after,
            "all_business_documents_unchanged": True, "ledger_after": limit})
        print(name + ": " + rules["summary"])

    child = None
    try:
        child = smoke.Child(jar, uri, args.port, "SUCCESS"); child.ready(base)
        too_large = {**body, "amount": "150.00"}
        inspect("capacity_rejection", smoke.http(base, "POST", "/api/trader/trade", too_large, "explain-" + uuid.uuid4().hex),
            "REJECTED", "decision.insufficient_capacity")
        ineligible = {**body, "instrumentVerificationRequest": {**body["instrumentVerificationRequest"], "instrument": "UNAPPROVED"}}
        inspect("eligibility_rejection", smoke.http(base, "POST", "/api/trader/trade", ineligible, "explain-" + uuid.uuid4().hex),
            "REJECTED", "decision.instrument_not_approved")
        inspect("recorded_execution", smoke.http(base, "POST", "/api/trader/trade", body, "explain-" + uuid.uuid4().hex),
            "EXECUTED", "execution.accepted")
        child.stop(); child = smoke.Child(jar, uri, args.port, "TIMEOUT_AFTER_SUCCESS"); child.ready(base)
        inspect("unknown_with_success_receipt", smoke.http(base, "POST", "/api/trader/trade", body, "explain-" + uuid.uuid4().hex),
            "UNKNOWN", "execution.unknown")
        child.stop(); child = smoke.Child(jar, uri, args.port, "REJECT"); child.ready(base)
        inspect("venue_rejection", smoke.http(base, "POST", "/api/trader/trade", body, "explain-" + uuid.uuid4().hex),
            "REJECTED", "execution.venue_rejected")
        report["passed"] = True
    finally:
        if child is not None:
            report["final_jvm_returncode"] = child.stop()
            report["own_child_cleanup_succeeded"] = child.process.poll() is not None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Run frozen offline cases and print sample explanations; no Docker or key")
    parser.add_argument("--jar", type=Path, default=ROOT / "target/atlas-trade-control-room-1.0.0-SNAPSHOT.jar")
    parser.add_argument("--mongo-container", default="atlas-ai-acceptance-20261020")
    parser.add_argument("--owner-value", default="20261020-ai-explanations")
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--output", type=Path, default=ROOT / "target/atlas-explanation-demo.json")
    args = parser.parse_args()
    if args.offline:
        offline(args); return
    report = {"schema_version": 1, "recorded_at": datetime.now(timezone.utc).isoformat(), "passed": False}
    try:
        local(args, report)
    except Exception as error:
        report["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(args.output)


if __name__ == "__main__":
    main()
