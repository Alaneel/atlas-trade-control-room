#!/usr/bin/env python3
"""Bounded local JVM crash/restart smoke against one explicitly owned Mongo container.

This checks a process kill after a persisted uncertain execution response. It does
not inject a kill at every instruction boundary or prove multi-node failover.
The generated database is retained for the caller's owned-container cleanup.
"""

from __future__ import annotations

import argparse
from collections import deque
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlunsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid


ROOT = Path(__file__).resolve().parents[1]
OWNED_CONTAINER = "atlas-acceptance-20261020"
OWNERSHIP_LABEL = "io.github.alaneel.atlas.task"
OWNERSHIP_VALUE = "20261020-reliable-execution"
LOCAL_HTTP = build_opener(ProxyHandler({}))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def command(*args: str) -> str:
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise RuntimeError(f"Local command {args[0]} failed: {result.stderr[-2000:]}")
    return result.stdout.strip()


def owned_container() -> None:
    data = json.loads(command("docker", "inspect", OWNED_CONTAINER))[0]
    require(data["Config"].get("Labels", {}).get(OWNERSHIP_LABEL) == OWNERSHIP_VALUE,
            "Refusing to inspect a Mongo container without the exact task ownership label")
    bindings = data["NetworkSettings"]["Ports"].get("27017/tcp", [])
    require(any(binding.get("HostIp") == "127.0.0.1" and binding.get("HostPort") == "28027"
                for binding in bindings), "Owned Mongo container must publish loopback port 28027")


def database_snapshot(database: str) -> dict:
    require(database.startswith("atlas_process_") and len(database) == 46
            and all(c in "0123456789abcdef" for c in database[len("atlas_process_"):]),
            "Unexpected database identity")
    # Database identity is generated here, never supplied as executable JavaScript.
    expression = (
        f"const d=db.getSiblingDB({json.dumps(database)});"
        "print(JSON.stringify({instruments:d.instruments.countDocuments({}),"
        "limits:d.limits.countDocuments({}),trades:d.reliable_trades.countDocuments({}),"
        "receipts:d.simulated_execution_receipts.countDocuments({}),"
        "events:d.trade_events.countDocuments({})}));"
    )
    return json.loads(command("docker", "exec", OWNED_CONTAINER, "mongosh", "--quiet", "--eval", expression))


def http(base: str, method: str, path: str, body: dict | None = None,
         key: str | None = None) -> tuple[int, dict | list]:
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if key is not None:
        headers["Idempotency-Key"] = key
    request = Request(base + path, data=data, method=method, headers=headers)
    try:
        with LOCAL_HTTP.open(request, timeout=10) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.load(error)


class Child:
    def __init__(self, jar: Path, uri: str, port: int, scenario: str):
        self.tail: deque[str] = deque(maxlen=80)
        args = ["java", "-Xms128m", "-Xmx384m", "-jar", str(jar),
                "--spring.profiles.active=dev", f"--spring.data.mongodb.uri={uri}",
                "--atlas.seed.enabled=true", "--atlas.recovery.enabled=false",
                f"--atlas.execution.scenario={scenario}", "--server.address=127.0.0.1",
                f"--server.port={port}", "--management.health.redis.enabled=false",
                "--management.endpoint.health.probes.enabled=true"]
        self.process = subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            self.tail.append(line.rstrip())

    def ready(self, base: str) -> None:
        deadline = time.monotonic() + 90
        last_error = "not started"
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("Owned JVM exited during startup: " + "\n".join(self.tail))
            try:
                health_status, health = http(base, "GET", "/actuator/health/readiness")
                status, limit = http(base, "GET", "/api/trader/limit/NORTHSTAR/EQUITY?currency=USD")
                if health_status == 200 and health.get("status") == "UP" and status == 200 and limit:
                    return
            except (URLError, OSError, ValueError) as error:
                last_error = str(error)
            time.sleep(0.2)
        raise TimeoutError(f"Owned JVM did not become ready: {last_error}\n" + "\n".join(self.tail))

    def stop(self, crash: bool = False) -> int:
        if self.process.poll() is None:
            # Popen references only this invocation's child PID. No process-group signals.
            if crash:
                self.process.kill()
            else:
                self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.reader.join(timeout=2)
        if self.process.stdout is not None:
            self.process.stdout.close()
        return self.process.returncode


def ledger(base: str, available: str, reserved: str, used: str) -> dict:
    status, limit = http(base, "GET", "/api/trader/limit/NORTHSTAR/EQUITY?currency=USD")
    require(status == 200 and isinstance(limit, dict), "Expected the seeded USD account")
    expected = {"availableLimit": available, "reservedLimit": reserved, "usedLimit": used, "totalLimit": "100.00"}
    for field, value in expected.items():
        require(Decimal(str(limit[field])) == Decimal(value), f"Unexpected {field}: {limit[field]}")
    require(sum(Decimal(str(limit[field])) for field in ("availableLimit", "reservedLimit", "usedLimit"))
            == Decimal(str(limit["totalLimit"])), "Ledger invariant failed")
    return limit


def run(args: argparse.Namespace, report: dict) -> None:
    jar = args.jar.resolve()
    require(jar.is_file(), f"Build the executable JAR first: {jar}")
    require(sys.platform != "win32", "This smoke records POSIX SIGKILL process behavior")
    owned_container()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))
    database = "atlas_process_" + uuid.uuid4().hex
    uri = urlunsplit(("mongodb", "127.0.0.1:28027", "/" + database,
                      urlencode({"replicaSet": "atlas-rs", "directConnection": "true"}), ""))
    base = f"http://127.0.0.1:{args.port}"
    key = "process-smoke-" + uuid.uuid4().hex
    body = {"instrumentVerificationRequest": {
        "instrumentGroup": "EQUITY", "instrument": "AAPL", "department": "GLOBAL_MARKETS",
        "country": "US", "exchange": "NASDAQ", "tradeCurrency": "USD", "settlementCurrency": "USD"},
        "counterparty": "NORTHSTAR", "amount": "10.00", "confirmed": True}
    report.update({"database": database, "database_cleanup": "retained for root-owned container cleanup",
                   "mongo_container": OWNED_CONTAINER, "http_base": base,
                   "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                   "source_revision": command("git", "rev-parse", "HEAD"),
                   "working_tree_dirty": bool(command("git", "status", "--porcelain")),
                   "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   "java_version": command("java", "--version"), "idempotency_key": key})
    child: Child | None = None
    try:
        child = Child(jar, uri, args.port, "TIMEOUT_AFTER_SUCCESS")
        child.ready(base)
        report["initial_jvm_pid"] = child.process.pid
        report["initial_counts"] = database_snapshot(database)
        report["initial_ledger"] = ledger(base, "100.00", "0.00", "0.00")
        status, result = http(base, "POST", "/api/trader/trade", body, key)
        require(status == 202 and result["status"] == "UNKNOWN", f"Expected UNKNOWN202, got {status}: {result}")
        trade_id = result["tradeId"]
        report["uncertain_response"] = {"http_status": status, **result}
        report["before_kill_ledger"] = ledger(base, "90.00", "10.00", "0.00")
        report["before_kill_counts"] = database_snapshot(database)
        require(report["before_kill_counts"]["trades"] == 1 and report["before_kill_counts"]["receipts"] == 1,
                "Exactly one durable trade and venue receipt must exist before killing the JVM")
        _, before_events = http(base, "GET", f"/api/trader/trades/{trade_id}/events")
        report["before_kill_events"] = before_events
        killed_code = child.stop(crash=True)
        require(killed_code == -signal.SIGKILL, f"Expected SIGKILL exit code, got {killed_code}")
        report["killed_jvm_returncode"] = killed_code
        child = None

        child = Child(jar, uri, args.port, "SUCCESS")
        child.ready(base)
        report["restarted_jvm_pid"] = child.process.pid
        report["after_restart_ledger"] = ledger(base, "90.00", "10.00", "0.00")
        after_restart = database_snapshot(database)
        require(after_restart == report["before_kill_counts"], "Restart/seed must not change records or outstanding money")
        report["after_restart_counts"] = after_restart
        status, persisted = http(base, "GET", f"/api/trader/trades/{trade_id}")
        require(status == 200 and persisted["status"] == "UNKNOWN", "The uncertain trade must survive process loss")
        report["persisted_status_after_restart"] = persisted["status"]
        status, reconciled = http(base, "POST", f"/api/trader/trades/{trade_id}/reconcile")
        require(status == 200 and reconciled["status"] == "EXECUTED", f"Expected reconciliation success: {reconciled}")
        report["reconciliation_response"] = {"http_status": status, **reconciled}
        for _ in range(20):
            replay_status, replay = http(base, "POST", "/api/trader/trade", body, key)
            require(replay_status == 200 and replay["tradeId"] == trade_id and replay["status"] == "EXECUTED",
                    "Same-key replay must return the same executed trade")
        report["replayed_requests"] = 20
        report["final_ledger"] = ledger(base, "90.00", "0.00", "10.00")
        report["final_counts"] = database_snapshot(database)
        require(report["final_counts"]["receipts"] == 1 and report["final_counts"]["trades"] == 1,
                "Recovery/replay must not create duplicate trades or venue effects")
        for field in ("instruments", "limits"):
            require(report["final_counts"][field] == report["initial_counts"][field], "Restart duplicated fixtures")
        _, final_events = http(base, "GET", f"/api/trader/trades/{trade_id}/events")
        sequences = [event["sequence"] for event in final_events]
        require(sequences == sorted(set(sequences)), "Event sequences must be unique and ordered")
        require(len(final_events) == len(before_events) + 1, "Only reconciliation may append the terminal event")
        report["final_events"] = final_events
        report["passed"] = True
    finally:
        if child is not None:
            report["final_jvm_returncode"] = child.stop()
            report["own_child_cleanup_succeeded"] = child.process.poll() is not None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/atlas-trade-control-room-1.0.0-SNAPSHOT.jar")
    parser.add_argument("--port", type=int, default=18081, choices=range(1024, 65536), metavar="PORT")
    parser.add_argument("--output", type=Path, default=ROOT / "target/atlas-process-recovery.json")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; preserve it and choose a fresh --output path")
    report = {"schema_version": 1, "recorded_at": datetime.now(timezone.utc).isoformat(), "passed": False,
              "scope": "Actual JVM SIGKILL after UNKNOWN response with persisted simulated execution, same-database restart, query-only reconciliation and identical-key replay",
              "limits": ["One process boundary after UNKNOWN202, not every crash boundary",
                         "Local Mongo replica set and simulated venue; no independent-node failover or real exchange",
                         "No production identity or authorization acceptance"]}
    code = 0
    try:
        run(args, report)
    except Exception as error:
        report["error_type"] = type(error).__name__
        report["error"] = str(error)
        code = 1
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "output": str(args.output), "database": report.get("database"),
                      "own_child_cleanup_succeeded": report.get("own_child_cleanup_succeeded"), "error": report.get("error")}))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
