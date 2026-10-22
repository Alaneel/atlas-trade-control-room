"""Deterministic, session-local demo decisions (never calls the trading API)."""

from copy import deepcopy
from datetime import datetime
from math import isfinite


DEMO_TRADES = [
    {"time": "09:42:18", "counterparty": "NORTHSTAR", "instrument": "AAPL", "group": "EQUITY", "amount": 1_250_000, "status": "APPROVED"},
    {"time": "09:38:04", "counterparty": "MERIDIAN", "instrument": "US10Y", "group": "BOND", "amount": 3_800_000, "status": "APPROVED"},
    {"time": "09:31:55", "counterparty": "NORTHSTAR", "instrument": "EURUSD", "group": "FX", "amount": 5_400_000, "status": "REVIEW"},
    {"time": "09:27:20", "counterparty": "APEX", "instrument": "MSFT", "group": "EQUITY", "amount": 920_000, "status": "APPROVED"},
    {"time": "09:14:07", "counterparty": "MERIDIAN", "instrument": "XAUUSD", "group": "COMMODITY", "amount": 2_100_000, "status": "DECLINED"},
]

DEMO_LIMITS = {
    ("NORTHSTAR", "EQUITY"): 18_750_000,
    ("NORTHSTAR", "FX"): 12_600_000,
    ("MERIDIAN", "BOND"): 31_200_000,
    ("APEX", "EQUITY"): 8_450_000,
}

DEMO_INSTRUMENTS = {"EQUITY": ("AAPL", "MSFT"), "BOND": ("US10Y",), "FX": ("EURUSD",), "COMMODITY": ("XAUUSD",)}


def new_demo_state():
    return {"trades": deepcopy(DEMO_TRADES), "limits": DEMO_LIMITS.copy()}


def submit_demo_trade(state, counterparty, group, instrument, amount):
    """Reserve once per submission; rejected/reviewed orders consume no capacity."""
    if not isinstance(amount, (int, float)) or not isfinite(amount) or amount <= 0:
        raise ValueError("Notional must be a finite number greater than zero.")
    if not instrument or not instrument.strip():
        raise ValueError("Enter an instrument.")

    key = (counterparty, group)
    available = state["limits"].get(key)
    if instrument not in DEMO_INSTRUMENTS.get(group, ()):
        status, message = "REVIEW", "Unknown demo instrument; added to the session's exception queue."
    elif available is None:
        status, message = "DECLINED", "No demo limit exists for this counterparty and product group."
    elif amount > available:
        status, message = "REVIEW", "Notional exceeds remaining capacity; added to the session's exception queue."
    else:
        state["limits"][key] -= amount
        status = "APPROVED"
        message = f"USD {amount:,.0f} reserved. Remaining capacity: USD {state['limits'][key]:,.0f}."

    state["trades"].insert(0, {
        "time": datetime.now().strftime("%H:%M:%S"), "counterparty": counterparty,
        "instrument": instrument, "group": group, "amount": amount, "status": status,
    })
    return status, message
