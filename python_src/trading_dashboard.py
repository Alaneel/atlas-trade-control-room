"""Streamlit control room for the Trade Approval Limit System.

The dashboard has a self-contained demo mode so reviewers can explore the
product without provisioning infrastructure. Connected mode talks to the
Spring Boot API and keeps the same interaction model.
"""

from __future__ import annotations

import os
from decimal import Decimal
from html import escape
from typing import Any
from urllib.parse import quote

import pandas as pd
import requests
import streamlit as st

from demo_state import DEMO_LIMITS, new_demo_state, submit_demo_trade
from money import format_money, money_string, parse_money, total_money
from trade_intent import prepare_intent


API_ROOT = os.getenv("BACKEND_API_URL", "http://localhost:8080").rstrip("/")
TRADER_API = f"{API_ROOT}/api/trader"

st.set_page_config(
    page_title="Atlas | Trade Control Room",
    page_icon="◈",
    layout="wide",
    initial_sidebar_state="expanded",
)

st.markdown(
    """
    <style>
      @import url('https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=Manrope:wght@400;500;600;700;800&display=swap');
      :root { --ink:#13231f; --muted:#687b74; --mint:#bdf9df; --green:#0b6b4d; --line:#dbe5e0; }
      html, body, [class*="css"] { font-family:'Manrope',sans-serif; }
      .stApp { background:#f4f7f5; color:var(--ink); }
      [data-testid="stSidebar"] { background:#10251f; border-right:1px solid #27443a; }
      [data-testid="stSidebar"] * { color:#eef8f3 !important; }
      [data-testid="stSidebar"] .stRadio label { padding:.35rem 0; }
      .block-container { max-width:1440px; padding:2rem 3rem 4rem; }
      h1,h2,h3 { color:var(--ink); letter-spacing:-.03em; }
      .eyebrow { font:500 .73rem 'DM Mono'; color:var(--green); letter-spacing:.14em; text-transform:uppercase; }
      .hero { padding:1.3rem 0 1.4rem; }
      .hero h1 { margin:.25rem 0 .35rem; font-size:2.65rem; line-height:1.05; }
      .hero p { color:var(--muted); max-width:720px; margin:0; font-size:1.02rem; }
      .metric-card { background:#fff; border:1px solid var(--line); border-radius:16px; padding:1.05rem 1.15rem; min-height:116px; box-shadow:0 8px 24px rgba(20,45,37,.04); }
      .metric-label { color:var(--muted); font-size:.74rem; font-weight:700; letter-spacing:.08em; text-transform:uppercase; }
      .metric-value { font:500 1.7rem 'DM Mono'; margin:.38rem 0 .2rem; }
      .metric-delta { color:var(--green); font-size:.78rem; }
      .status-dot { display:inline-block; width:8px; height:8px; border-radius:50%; background:#54e2aa; margin-right:7px; box-shadow:0 0 0 4px rgba(84,226,170,.12); }
      .callout { border:1px solid #b9dfce; background:#eafaf2; padding:.9rem 1rem; border-radius:12px; color:#245c48; margin:.6rem 0 1.2rem; }
      .mono { font-family:'DM Mono'; }
      div[data-testid="stForm"] { background:#fff; border:1px solid var(--line); border-radius:16px; padding:1.25rem; }
      div[data-testid="stDataFrame"] { border:1px solid var(--line); border-radius:14px; overflow:hidden; }
      .stButton>button, .stFormSubmitButton>button { border-radius:10px; border:0; background:#0b6b4d; color:white; font-weight:700; }
      .stButton>button:hover, .stFormSubmitButton>button:hover { background:#084f3a; color:white; border:0; }
      footer { visibility:hidden; }
      @media(max-width: 700px) { .block-container{padding:1.2rem;} .hero h1{font-size:2rem;} }
    </style>
    """,
    unsafe_allow_html=True,
)


TRADE_COLUMNS = ["time", "counterparty", "instrument", "group", "amount", "status"]


def api(method: str, path: str, *, include_status: bool = False, **kwargs: Any) -> Any:
    response = requests.request(method, f"{TRADER_API}{path}", timeout=8, **kwargs)
    response.raise_for_status()
    body = response.json(parse_float=Decimal)
    return (response.status_code, body) if include_status else body


def metric(label: str, value: str, delta: str) -> None:
    st.markdown(
        f'<div class="metric-card"><div class="metric-label">{escape(label)}</div>'
        f'<div class="metric-value">{escape(value)}</div><div class="metric-delta">{escape(delta)}</div></div>',
        unsafe_allow_html=True,
    )


def normalized_trades(raw: list[dict[str, Any]]) -> pd.DataFrame:
    rows = []
    for trade in raw:
        request = trade.get("instrumentVerificationRequest", {}) or {}
        rows.append({
            "time": str(trade.get("timestamp", "—")).replace("T", " ")[:19],
            "counterparty": trade.get("counterparty", "—"),
            "instrument": request.get("instrument", request.get("instrumentGroup", "—")),
            "group": request.get("instrumentGroup", "—"),
            "amount": trade.get("amount", 0),
            "status": trade.get("status", "—"),
        })
    result = pd.DataFrame(rows, columns=TRADE_COLUMNS)
    result["amount"] = result["amount"].map(lambda value: parse_money(value, allow_zero=True))
    return result


def api_error(exc: requests.RequestException) -> str:
    """Keep backend validation messages visible instead of only showing HTTP 400."""
    if exc.response is not None:
        try:
            body = exc.response.json()
            message = body.get("message") or str(exc)
            details = body.get("details")
            if isinstance(details, list) and details:
                message += ": " + "; ".join(str(item) for item in details)
            return message
        except (ValueError, AttributeError):
            pass
    return str(exc)


def transmit_intent(intent: dict[str, Any]) -> None:
    """A timeout never creates a new identity or changes the saved payload."""
    intent["state"], intent["error"], intent["result"] = "pending", None, None
    try:
        code, result = api("POST", "/trade", include_status=True,
                           json=intent["payload"], headers={"Idempotency-Key": intent["key"]})
        if not isinstance(result, dict) or result.get("status") not in {"IN_PROGRESS", "EXECUTED", "FAILED", "REJECTED"}:
            intent["error"] = "The API did not return a recognized trade decision. Retry the saved request to confirm its outcome."
            return
        intent["result"] = result
        if code == 200 and result["status"] in {"EXECUTED", "FAILED", "REJECTED"}:
            intent["state"] = "complete"
        elif code != 202 and result["status"] != "IN_PROGRESS":
            intent["error"] = "The API returned an unexpected response code. Retry the saved request to confirm its outcome."
    except requests.RequestException as exc:
        intent["error"] = api_error(exc)
        if exc.response is not None and 400 <= exc.response.status_code < 500:
            intent["state"] = "error"
    except (ValueError, TypeError):
        intent["error"] = "The API response was not valid trade JSON. Retry the saved request to confirm its outcome."


def show_intent(intent: dict[str, Any]) -> None:
    result = intent.get("result") or {}
    if intent["state"] == "pending":
        st.warning("This trade has no confirmed final result. Retry the saved request to check it without creating another trade.")
        if isinstance(result.get("message"), str) and result["message"].strip():
            st.info(result["message"])
        if intent.get("error"):
            st.caption(f"Last attempt: {intent['error']}")
        with st.expander("Saved trade for retry"):
            st.json(intent["payload"])
            st.caption(f"Request identity: {intent['key']}")
        if st.button("Retry same trade", use_container_width=True):
            transmit_intent(intent)
            st.rerun()
        st.caption("Retry uses the saved request. To submit edited fields, use Run checks & submit. A new intent can consume capacity again if the previous trade completed.")
    elif intent["state"] == "error":
        st.error(f"Trade could not be submitted: {intent.get('error', 'Request failed')}")
    else:
        message = f"{result.get('status', 'Submitted')} · {result.get('message', '')}"
        if result.get("status") == "EXECUTED":
            st.success(message)
        else:
            st.warning(message)
        st.caption("Submitting identical fields reuses this request identity. Start a new intent for another trade with the same details.")
    if st.button("Start a new trade intent"):
        st.session_state.api_trade_intent = None
        st.rerun()


def instrument_fields(prefix: str) -> dict[str, str]:
    """The default is an exact combination from the bundled instrument CSV."""
    a, b = st.columns(2)
    with a:
        group = st.text_input("Product group", "Bonds", key=f"{prefix}_group")
        instrument = st.text_input("Instrument", "0% Convertible Bonds", key=f"{prefix}_instrument")
        department = st.text_input("Department", "RE", key=f"{prefix}_department")
        country = st.text_input("Risk country", "ASIA PACIFIC REGION", key=f"{prefix}_country")
    with b:
        exchange = st.text_input("Venue", "Colombo", key=f"{prefix}_exchange")
        trade_currency = st.text_input("Trade currency", "CHF", key=f"{prefix}_trade_currency")
        settlement_currency = st.text_input("Settlement currency", "MXN", key=f"{prefix}_settlement_currency")
    return {"instrumentGroup": group, "instrument": instrument, "department": department,
            "country": country, "exchange": exchange, "tradeCurrency": trade_currency,
            "settlementCurrency": settlement_currency}


if "atlas_demo" not in st.session_state:
    st.session_state.atlas_demo = new_demo_state()
if "api_trade_intent" not in st.session_state:
    st.session_state.api_trade_intent = None

with st.sidebar:
    st.markdown("## ◈ ATLAS")
    st.caption("TRADE CONTROL ROOM")
    st.markdown("---")
    page = st.radio("Workspace", ["Overview", "New trade", "Limit explorer", "Approvals"], label_visibility="collapsed")
    st.markdown("---")
    demo_mode = st.toggle("Demo data", value=os.getenv("DEMO_MODE", "true").lower() == "true")
    if demo_mode:
        st.markdown('<span class="status-dot"></span>Demo environment', unsafe_allow_html=True)
        st.caption("No backend required")
        st.caption("Synthetic, session-local data. Demo decisions are not backend decisions.")
        if st.button("Reset demo", use_container_width=True):
            st.session_state.atlas_demo = new_demo_state()
            st.rerun()
    else:
        try:
            healthy = requests.get(f"{API_ROOT}/actuator/health", timeout=2).ok
        except requests.RequestException:
            healthy = False
        status = "API connected" if healthy else "API unavailable"
        st.markdown(f'<span class="status-dot"></span>{status}', unsafe_allow_html=True)
        st.caption(API_ROOT)
    st.markdown("---")
    st.caption("Spring Boot · MongoDB · Redis")

st.markdown(
    '<div class="hero"><div class="eyebrow">Pre-trade workflow / prototype</div>'
    '<h1>Trade decisions, without the guesswork.</h1>'
    '<p>Explore instrument eligibility, counterparty capacity and exception requests in one control room.</p></div>',
    unsafe_allow_html=True,
)

if demo_mode:
    trades = pd.DataFrame(st.session_state.atlas_demo["trades"], columns=TRADE_COLUMNS)
else:
    try:
        trades = normalized_trades(api("GET", "/trades"))
    except (requests.RequestException, ValueError):
        trades = pd.DataFrame(columns=["time", "counterparty", "instrument", "group", "amount", "status"])
        st.warning("The API is unavailable. Start the backend or enable Demo data in the sidebar.")

if page == "Overview":
    total = total_money(trades["amount"]) if not trades.empty else Decimal(0)
    approved = int(trades["status"].isin(["APPROVED", "EXECUTED"]).sum()) if not trades.empty else 0
    review = int((trades["status"].isin(["REVIEW", "PENDING"])).sum()) if not trades.empty else 0
    approval_rate = approved / len(trades) * 100 if len(trades) else 0
    c1, c2, c3, c4 = st.columns(4)
    with c1: metric("Recorded notional", format_money(total), "Demo USD" if demo_mode else "No FX conversion applied")
    with c2: metric("Recorded success rate", f"{approval_rate:.0f}%", f"{approved} of {len(trades)} records")
    with c3: metric("Demo review queue" if demo_mode else "Approval queue", str(review) if demo_mode else "—", "Session exceptions" if demo_mode else "No queue API available")
    with c4: metric("Recorded trades", str(len(trades)), "Synthetic sample + this session" if demo_mode else "Stored decisions")

    st.markdown("### Activity")
    left, right = st.columns([1.65, 1])
    with left:
        if trades.empty:
            st.info("No trades have been recorded yet.")
        else:
            display = trades.copy()
            display["amount"] = display["amount"].map(format_money)
            display.columns = ["Time", "Counterparty", "Instrument", "Group", "Notional", "Decision"]
            st.dataframe(display, hide_index=True, use_container_width=True)
    with right:
        st.markdown("#### Recorded notional by product")
        if not trades.empty:
            chart = trades.groupby("group", as_index=False)["amount"].agg(total_money).set_index("group")
            chart["amount"] = chart["amount"].map(float)
            st.bar_chart(chart, color="#0b6b4d", height=250)
        st.caption("Chart values are approximate floats for display only. Includes failures; not a live exposure measure. Tables retain exact decimals.")

elif page == "New trade":
    st.markdown("### Pre-trade check")
    st.caption("Demo checks are deterministic. API mode validates the full instrument definition and simulates execution.")
    with st.form("trade_form"):
        if demo_mode:
            a, b = st.columns(2)
            with a:
                counterparty = st.selectbox("Counterparty", ["NORTHSTAR", "MERIDIAN", "APEX"])
                instrument_group = st.selectbox("Product group", ["EQUITY", "BOND", "FX", "COMMODITY"])
            with b:
                instrument = st.text_input("Instrument", "AAPL")
                amount = st.text_input("Notional (USD)", "500000", help="Exact decimal; at most 26 integer and 8 fractional digits. No rounding.")
        else:
            st.caption("Defaults match the bundled CSV seed. Change any field to test another combination.")
            counterparty = st.text_input("Counterparty", "Abbey National Treasury Services PLC")
            request = instrument_fields("trade")
            amount = st.text_input("Notional in the counterparty limit currency", "500000", help="Exact decimal; at most 26 integer and 8 fractional digits. No rounding.")
        submitted = st.form_submit_button("Run checks & submit", use_container_width=True)

    if submitted:
        if demo_mode:
            try:
                status, message = submit_demo_trade(st.session_state.atlas_demo, counterparty, instrument_group, instrument, amount)
                if status == "APPROVED":
                    st.success(f"Demo approved · {message}")
                elif status == "REVIEW":
                    st.warning(message)
                else:
                    st.error(message)
            except ValueError as exc:
                st.error(str(exc))
        else:
            try:
                payload = {"instrumentVerificationRequest": request, "counterparty": counterparty,
                           "amount": money_string(amount)}
                intent = prepare_intent(st.session_state.api_trade_intent, payload)
                st.session_state.api_trade_intent = intent
                transmit_intent(intent)
            except ValueError as exc:
                st.error(str(exc))
    if not demo_mode and st.session_state.api_trade_intent is not None:
        show_intent(st.session_state.api_trade_intent)

elif page == "Limit explorer":
    st.markdown("### Counterparty capacity")
    x, y, z = st.columns([1, 1, .6])
    with x:
        counterparty = st.selectbox("Counterparty", ["NORTHSTAR", "MERIDIAN", "APEX"]) if demo_mode else st.text_input("Counterparty", "Abbey National Treasury Services PLC")
    with y:
        instrument_group = st.selectbox("Product group", ["EQUITY", "BOND", "FX", "COMMODITY"]) if demo_mode else st.text_input("Product group", "Bonds")
    with z:
        st.write("")
        st.write("")
        lookup = st.button("Check limit", use_container_width=True)
    if lookup:
        if demo_mode:
            key = (counterparty, instrument_group)
            available = st.session_state.atlas_demo["limits"].get(key)
            if available is None:
                st.warning("No demo limit exists for this counterparty and product group.")
            else:
                st.metric("Available capacity", f"USD {format_money(available)}")
                st.progress(min(float(available / DEMO_LIMITS[key]), 1.0), text="Approximate share of opening demo capacity")
        else:
            try:
                result = api("GET", f"/limit/{quote(counterparty, safe='')}/{quote(instrument_group, safe='')}")
                st.metric("Available capacity", f"{result.get('currency', '')} {format_money(result.get('availableLimit', 0))}")
            except (requests.RequestException, ValueError) as exc:
                st.error(f"Limit could not be loaded: {api_error(exc) if isinstance(exc, requests.RequestException) else exc}")

else:
    st.markdown("### Exception queue")
    if demo_mode:
        approvals = trades[trades["status"] == "REVIEW"]
        if approvals.empty:
            st.info("No demo exceptions in this session.")
        else:
            st.dataframe(approvals, hide_index=True, use_container_width=True)
        st.caption("Synthetic sample exceptions plus trades routed to review in this session. Reviewer decisions are not implemented.")
    else:
        st.info("The API supports creating pending requests. Listing requests and reviewer decisions are not implemented.")
        with st.form("approval_form"):
            request = instrument_fields("approval")
            submitted = st.form_submit_button("Create pending approval request")
        if submitted:
            try:
                result = api("POST", "/approval-request", json=request)
                st.success("Pending approval request created.")
                st.json(result)
            except requests.RequestException as exc:
                st.error(f"Approval request could not be created: {api_error(exc)}")
