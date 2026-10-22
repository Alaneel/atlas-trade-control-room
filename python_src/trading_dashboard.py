"""Streamlit control room for the Trade Approval Limit System.

The dashboard has a self-contained demo mode so reviewers can explore the
product without provisioning infrastructure. Connected mode talks to the
Spring Boot API and keeps the same interaction model.
"""

from __future__ import annotations

import os
from html import escape
from typing import Any
from urllib.parse import quote

import pandas as pd
import requests
import streamlit as st

from demo_state import DEMO_LIMITS, new_demo_state, submit_demo_trade


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


def api(method: str, path: str, **kwargs: Any) -> Any:
    response = requests.request(method, f"{TRADER_API}{path}", timeout=8, **kwargs)
    response.raise_for_status()
    return response.json()


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
    result["amount"] = pd.to_numeric(result["amount"], errors="coerce").fillna(0)
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
    except requests.RequestException:
        trades = pd.DataFrame(columns=["time", "counterparty", "instrument", "group", "amount", "status"])
        st.warning("The API is unavailable. Start the backend or enable Demo data in the sidebar.")

if page == "Overview":
    total = float(trades["amount"].sum()) if not trades.empty else 0
    approved = int(trades["status"].isin(["APPROVED", "EXECUTED"]).sum()) if not trades.empty else 0
    review = int((trades["status"].isin(["REVIEW", "PENDING"])).sum()) if not trades.empty else 0
    approval_rate = approved / len(trades) * 100 if len(trades) else 0
    c1, c2, c3, c4 = st.columns(4)
    with c1: metric("Recorded notional", f"{total / 1_000_000:.1f}M", "Demo USD" if demo_mode else "No FX conversion applied")
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
            display["amount"] = display["amount"].map(lambda value: f"{value:,.0f}")
            display.columns = ["Time", "Counterparty", "Instrument", "Group", "Notional", "Decision"]
            st.dataframe(display, hide_index=True, use_container_width=True)
    with right:
        st.markdown("#### Recorded notional by product")
        if not trades.empty:
            chart = trades.groupby("group", as_index=False)["amount"].sum().set_index("group")
            st.bar_chart(chart, color="#0b6b4d", height=250)
        st.caption("Includes all recorded outcomes, including failures; not a live exposure measure.")

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
                amount = st.number_input("Notional (USD)", min_value=1_000.0, value=500_000.0, step=50_000.0)
        else:
            st.caption("Defaults match the bundled CSV seed. Change any field to test another combination.")
            counterparty = st.text_input("Counterparty", "Abbey National Treasury Services PLC")
            request = instrument_fields("trade")
            amount = st.number_input("Notional in the counterparty limit currency", min_value=1.0, value=500_000.0, step=50_000.0)
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
                result = api("POST", "/trade", json={"instrumentVerificationRequest": request, "counterparty": counterparty, "amount": amount})
                message = f"{result.get('status', 'Submitted')} · {result.get('message', '')}"
                if result.get("status") == "EXECUTED":
                    st.success(message)
                else:
                    st.warning(message)
            except requests.RequestException as exc:
                st.error(f"Trade could not be submitted: {api_error(exc)}")

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
                st.metric("Available capacity", f"USD {available:,.0f}")
                st.progress(min(available / DEMO_LIMITS[key], 1.0), text="Remaining against the opening demo capacity")
        else:
            try:
                result = api("GET", f"/limit/{quote(counterparty, safe='')}/{quote(instrument_group, safe='')}")
                st.metric("Available capacity", f"{result.get('currency', '')} {result.get('availableLimit', 0):,.0f}")
            except requests.RequestException as exc:
                st.error(f"Limit could not be loaded: {api_error(exc)}")

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
