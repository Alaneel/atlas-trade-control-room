"""Exercise decimal accounting and retry safety on a disposable seeded stack.

This submits one simulated trade using the repository's CSV fixtures. It needs
only Python's standard library and is intended for the Docker CI smoke stack.
"""

import argparse
import json
import sys
import uuid
from decimal import Decimal
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen


COUNTERPARTY = "Abbey National Treasury Services PLC"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def payload(amount):
    # Keep the exact decimal literal on the wire; JSON's float encoder would
    # erase the different 0.10/0.100 representations used by the replay check.
    return (
        '{"counterparty":"' + COUNTERPARTY + '","amount":' + amount
        + ',"instrumentVerificationRequest":{"instrumentGroup":"Bonds",'
        '"instrument":"0% Convertible Bonds","settlementCurrency":"MXN",'
        '"tradeCurrency":"CHF","country":"ASIA PACIFIC REGION",'
        '"exchange":"Colombo","department":"RE"}}'
    ).encode("utf-8")


def smoke(base_url):
    api_url = base_url.rstrip("/") + "/api/trader"
    key = "atlas-smoke-" + uuid.uuid4().hex

    def call(path, amount=None):
        headers = {}
        body = None
        if amount is not None:
            headers = {"Content-Type": "application/json", "Idempotency-Key": key}
            body = payload(amount)
        request = Request(api_url + path, data=body, headers=headers)
        try:
            response = urlopen(request, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            data = json.loads(response.read(), parse_float=Decimal)
            return response.status, response.headers, data

    limit_path = "/limit/" + quote(COUNTERPARTY, safe="") + "/Bonds"
    status, _, before_limit = call(limit_path)
    require(status == 200, "Seeded counterparty limit is unavailable: " + str(before_limit))
    status, _, before_history = call("/trades")
    require(status == 200 and isinstance(before_history, list), "Trade history is unavailable")

    status, headers, first = call("/trade", "0.10")
    require(status == 200, "First submission failed: " + str(first))
    require(first.get("status") in {"EXECUTED", "FAILED"},
            "Seeded trade did not reach a simulated execution outcome: " + str(first))
    require(headers.get("Idempotency-Replayed") == "false", "First submission was marked as replayed")
    operation_id = headers.get("X-Trade-Operation-Id")
    require(bool(operation_id), "First submission omitted its operation ID")

    status, headers, replay = call("/trade", "0.100")
    require(status == 200 and replay == first, "Canonical decimal retry did not replay the completed result")
    require(headers.get("Idempotency-Replayed") == "true", "Retry was not marked as replayed")
    require(headers.get("X-Trade-Operation-Id") == operation_id, "Retry created a different operation")

    status, _, conflict = call("/trade", "0.20")
    require(status == 409 and conflict.get("error") == "IDEMPOTENCY_CONFLICT",
            "Changed payload did not produce the expected conflict: " + str(conflict))

    status, _, after_limit = call(limit_path)
    require(status == 200, "Limit read failed after the submissions")
    expected_debit = Decimal("0.10") if first["status"] == "EXECUTED" else Decimal("0")
    actual_debit = Decimal(str(before_limit["availableLimit"])) - Decimal(str(after_limit["availableLimit"]))
    require(actual_debit == expected_debit,
            "Incorrect exact capacity debit: expected " + str(expected_debit) + ", got " + str(actual_debit))

    status, _, after_history = call("/trades")
    require(status == 200 and isinstance(after_history, list), "History read failed after the submissions")
    require(len(after_history) == len(before_history) + 1, "Replay or conflict added an extra history record")
    prior_ids = {row["id"] for row in before_history}
    new_rows = [row for row in after_history if row["id"] not in prior_ids]
    require(len(new_rows) == 1, "Could not identify the single new history record")
    require(new_rows[0]["id"] == operation_id, "History does not correlate with the durable operation")
    require(Decimal(str(new_rows[0]["amount"])) == Decimal("0.10"), "History amount is not exact")
    require(new_rows[0]["status"] == first["status"], "History outcome differs from the completed result")
    print("Trade smoke passed (" + first["status"] + "): exact decimal capacity, canonical replay, conflict, single history")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080", help="Root URL of the disposable API stack")
    args = parser.parse_args()
    try:
        smoke(args.base_url)
    except Exception as error:
        print("Trade smoke failed: " + type(error).__name__ + ": " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
