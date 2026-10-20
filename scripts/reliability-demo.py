#!/usr/bin/env python3
"""Bounded local HTTP acceptance demo. Requires fresh NORTHSTAR 100.00 development fixtures."""
import argparse
import concurrent.futures
import json
import platform
import sys
import time
import uuid
from collections import Counter
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


class Demo:
    def __init__(self, base_url, output):
        parsed = urlparse(base_url)
        if (parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
                or parsed.username or parsed.password or parsed.query or parsed.fragment
                or parsed.path not in {"", "/"}):
            raise ValueError("--base-url must be a loopback http origin without credentials or a path")
        self.base = base_url.rstrip("/")
        self.output = output
        self.run_id = "demo-" + uuid.uuid4().hex
        self.record = {
            "schema_version": 1, "run_id": self.run_id,
            "scope": "local HTTP API with persisted simulated execution, not a real venue or capacity benchmark",
            "python": platform.python_version(), "requests": [], "checks": {},
        }
        self.request = {
            "counterparty": "NORTHSTAR", "amount": "10.00", "confirmed": True,
            "instrumentVerificationRequest": {
                "instrumentGroup": "EQUITY", "instrument": "AAPL", "department": "GLOBAL_MARKETS",
                "country": "US", "exchange": "NASDAQ", "tradeCurrency": "USD", "settlementCurrency": "USD",
            },
        }

    def call(self, method, path, body=None, key=None):
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if key is not None:
            headers["Idempotency-Key"] = key
        request = Request(self.base + path, data=None if body is None else json.dumps(body).encode(),
                          method=method, headers=headers)
        start = time.perf_counter()
        try:
            with urlopen(request, timeout=15) as response:
                code, data = response.status, response.read(1_048_577)
        except HTTPError as response:
            code, data = response.code, response.read(1_048_577)
        if len(data) > 1_048_576:
            raise RuntimeError("API response exceeded demo bound")
        elapsed = (time.perf_counter() - start) * 1000
        result = json.loads(data)
        return {"http_status": code, "latency_ms": elapsed, "body": result, "key": key}

    def check(self, name, condition):
        self.record["checks"][name] = bool(condition)
        if not condition:
            raise AssertionError(name)

    def ledger(self):
        response = self.call("GET", "/api/trader/limit/NORTHSTAR/EQUITY?currency=USD")
        self.check("limit_readable", response["http_status"] == 200 and response["body"] is not None)
        return response["body"]

    def values(self, ledger):
        return tuple(Decimal(str(ledger[name])) for name in
                     ["totalLimit", "availableLimit", "reservedLimit", "usedLimit"])

    def precondition(self):
        ledger = self.ledger()
        self.record["ledger_before"] = ledger
        self.check("fresh_100_unit_fixture", self.values(ledger) == (Decimal("100"), Decimal("100"), Decimal("0"), Decimal("0")))

    def concurrency(self):
        def submit(index):
            return self.call("POST", "/api/trader/trade", self.request, self.run_id + "-" + str(index))
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(submit, range(100)))
        self.record["requests"] = results
        self.record["http_status_counts"] = dict(Counter(str(result["http_status"]) for result in results))
        self.check("all_submissions_accounted", all(result["http_status"] in {200, 202} for result in results))
        # Observe pending trades; never hide request errors by resubmitting them.
        deadline = time.monotonic() + 20
        for result in results:
            trade_id = result["body"].get("tradeId")
            self.check("trade_identity_retained", bool(trade_id))
            current = result["body"]
            while current["status"] not in {"EXECUTED", "REJECTED"}:
                if time.monotonic() >= deadline:
                    raise AssertionError("pending trade did not reach a terminal state within the demo bound")
                time.sleep(0.05)
                observed = self.call("GET", "/api/trader/trades/" + trade_id)
                self.check("pending_trade_readable", observed["http_status"] == 200)
                current = observed["body"]
            result["observed_terminal_status"] = current["status"]
        counts = Counter(result["observed_terminal_status"] for result in results)
        self.record["terminal_status_counts"] = dict(counts)
        self.check("ten_executed_ninety_rejected", counts == {"EXECUTED": 10, "REJECTED": 90})
        winner = next(result for result in results if result["observed_terminal_status"] == "EXECUTED")
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            replays = list(pool.map(lambda _: self.call("POST", "/api/trader/trade", self.request, winner["key"]), range(100)))
        self.record["replay_status_counts"] = dict(Counter(str(result["http_status"]) for result in replays))
        self.check("hundred_replays_same_terminal_trade", all(result["http_status"] == 200
                   and result["body"].get("tradeId") == winner["body"]["tradeId"]
                   and result["body"].get("status") == "EXECUTED" for result in replays))
        changed = dict(self.request, amount="11.00")
        conflict = self.call("POST", "/api/trader/trade", changed, winner["key"])
        self.check("changed_payload_conflicts", conflict["http_status"] == 409)
        self.check("missing_key_rejected", self.call("POST", "/api/trader/trade", self.request)["http_status"] == 400)
        self.check("fractional_cent_rejected", self.call("POST", "/api/trader/trade", dict(self.request, amount="0.001"),
                                                        self.run_id + "-fraction")["http_status"] == 400)
        ledger = self.ledger()
        self.record["ledger_after"] = ledger
        self.check("exact_money_conservation", self.values(ledger) == (Decimal("100"), Decimal("0"), Decimal("0"), Decimal("100")))
        events = self.call("GET", "/api/trader/trades/" + winner["body"]["tradeId"] + "/events")
        self.record["successful_trade_events"] = events["body"]
        self.check("ordered_audit_events", events["http_status"] == 200 and len(events["body"]) >= 3
                   and events["body"][-1]["status"] == "EXECUTED")

    def timeout(self):
        result = self.call("POST", "/api/trader/trade", self.request, self.run_id)
        self.record["requests"] = [result]
        self.check("unknown_outcome_visible", result["http_status"] == 202 and result["body"].get("status") == "UNKNOWN")
        ledger = self.ledger()
        self.record["ledger_while_unknown"] = ledger
        self.check("unknown_keeps_reservation", self.values(ledger) == (Decimal("100"), Decimal("90"), Decimal("10"), Decimal("0")))
        trade_id = result["body"]["tradeId"]
        first = self.call("POST", "/api/trader/trades/" + trade_id + "/reconcile")
        self.record["reconciliation"] = first
        self.check("persisted_receipt_settles", first["http_status"] == 200 and first["body"].get("status") == "EXECUTED")
        for _ in range(20):
            replay = self.call("POST", "/api/trader/trades/" + trade_id + "/reconcile")
            self.check("reconciliation_replay_stays_terminal", replay["http_status"] == 200 and replay["body"].get("status") == "EXECUTED")
        ledger = self.ledger()
        self.record["ledger_after"] = ledger
        self.check("one_exact_settlement", self.values(ledger) == (Decimal("100"), Decimal("90"), Decimal("0"), Decimal("10")))
        self.record["successful_trade_events"] = self.call("GET", "/api/trader/trades/" + trade_id + "/events")["body"]

    def save(self):
        self.output.parent.mkdir(parents=True, exist_ok=True)
        self.output.write_text(json.dumps(self.record, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--mode", choices=["concurrency", "timeout"], default="concurrency")
    parser.add_argument("--output", type=Path, default=Path("target/atlas-http-demo.json"))
    arguments = parser.parse_args()
    demo = Demo(arguments.base_url, arguments.output)
    try:
        demo.record["mode"] = arguments.mode
        demo.precondition()
        getattr(demo, arguments.mode)()
        demo.record["passed"] = True
    except Exception as exception:
        demo.record["passed"] = False
        demo.record["failure"] = str(exception)
        demo.record["failure_type"] = type(exception).__name__
        print("Demo failed: " + str(exception), file=sys.stderr)
    finally:
        demo.save()
    print(str(arguments.output))
    return 0 if demo.record.get("passed") else 1


if __name__ == "__main__":
    raise SystemExit(main())
