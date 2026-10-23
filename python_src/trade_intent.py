"""Preserve the identity and payload of a trade across uncertain API responses."""

from copy import deepcopy
import json
from uuid import uuid4


def prepare_intent(previous, payload):
    fingerprint = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    if previous is not None and previous["fingerprint"] == fingerprint:
        return previous
    return {"key": str(uuid4()), "fingerprint": fingerprint, "payload": deepcopy(payload),
            "state": "pending", "result": None}
