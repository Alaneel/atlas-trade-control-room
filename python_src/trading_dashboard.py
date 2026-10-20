"""Streamlit control room for the Trade Approval Limit System.

The dashboard has a self-contained demo mode so reviewers can explore the
product without provisioning infrastructure. Connected mode talks to the
Spring Boot API and keeps the same interaction model.
"""

from __future__ import annotations

import os
import uuid
from decimal import Decimal
from urllib.parse import quote
from datetime import datetime, timedelta
from typing import Any

import pandas as pd
import requests
import streamlit as st


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


DEMO_TRADES = [
    {"time": "09:42:18", "counterparty": "NORTHSTAR", "instrument": "AAPL", "group": "EQUITY", "amount": 1_250_000, "status": "APPROVED"},
    {"time": "09:38:04", "counterparty": "MERIDIAN", "instrument": "US10Y", "group": "BOND", "amount": 3_800_000, "status": "APPROVED"},
    {"time": "09:31:55", "counterparty": "NORTHSTAR", "instrument": "EURUSD", "group": "FX", "amount": 5_400_000, "status": "REVIEW"},
    {"time": "09:27:20", "counterparty": "APEX", "instrument": "MSFT", "group": "EQUITY", "amount": 920_000, "status": "APPROVED"},
    {"time": "09:14:07", "counterparty": "MERIDIAN", "instrument": "XAUUSD", "group": "COMMODITY", "amount": 2_100_000, "status": "DECLINED"},
]

DEMO_LIMITS = {
    ("NORTHSTAR", "EQUITY"): (18_750_000, "USD"),
    ("NORTHSTAR", "FX"): (12_600_000, "USD"),
    ("MERIDIAN", "BOND"): (31_200_000, "USD"),
    ("APEX", "EQUITY"): (8_450_000, "USD"),
}


def api(method: str, path: str, **kwargs: Any) -> Any:
    response = requests.request(method, f"{TRADER_API}{path}", timeout=8, **kwargs)
    response.raise_for_status()
    return response.json()


def submission_error(exc: requests.RequestException) -> None:
    code = exc.response.status_code if exc.response is not None else None
    if code is not None and 400 <= code < 500 and code not in (408, 429):
        st.session_state.pop("pending_submission", None)
        st.error(f"The request was rejected (HTTP {code}). Correct the request before submitting again.")
    else:
        st.error("The submission outcome may be unknown. Retry the pending submission to retrieve the same trade.")


def metric(label: str, value: str, delta: str) -> None:
    st.markdown(
        f'<div class="metric-card"><div class="metric-label">{label}</div>'
        f'<div class="metric-value">{value}</div><div class="metric-delta">{delta}</div></div>',
        unsafe_allow_html=True,
    )


def normalized_trades(raw: list[dict[str, Any]]) -> pd.DataFrame:
    rows = []
    for trade in raw:
        request = trade.get("instrumentVerificationRequest", {}) or {}
        rows.append({
            "time": str(trade.get("timestamp", trade.get("createdAt", "—"))).replace("T", " ")[:19],
            "counterparty": trade.get("counterparty", "—"),
            "instrument": request.get("instrument", request.get("instrumentGroup", "—")),
            "group": request.get("instrumentGroup", "—"),
            "amount": float(trade.get("amount", 0)),
            "status": trade.get("status", "—"),
        })
    return pd.DataFrame(rows)


if "demo_trades" not in st.session_state:
    st.session_state.demo_trades = DEMO_TRADES.copy()

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
    '<div class="hero"><div class="eyebrow">Risk operations / live desk</div>'
    '<h1>Trade decisions, without the guesswork.</h1>'
    '<p>Validate instruments, inspect counterparty exposure and route exceptions through one auditable control plane.</p></div>',
    unsafe_allow_html=True,
)

if demo_mode:
    trades = pd.DataFrame(st.session_state.demo_trades)
else:
    try:
        trades = normalized_trades(api("GET", "/trades"))
    except requests.RequestException:
        trades = pd.DataFrame(columns=["time", "counterparty", "instrument", "group", "amount", "status"])
        st.warning("The API is unavailable. Start the backend or enable Demo data in the sidebar.")

if page == "Overview":
    total = float(trades["amount"].sum()) if not trades.empty else 0
    approved = int((trades["status"].isin(["APPROVED", "EXECUTED"])).sum()) if not trades.empty else 0
    review = int((trades["status"].isin(["REVIEW", "PENDING", "RESERVED", "SUBMITTED", "UNKNOWN"])).sum()) if not trades.empty else 0
    approval_rate = approved / len(trades) * 100 if len(trades) else 0
    c1, c2, c3, c4 = st.columns(4)
    with c1: metric("Gross notional", f"${total / 1_000_000:.1f}M", "Sample data" if demo_mode else "Recorded submissions")
    with c2: metric("Approval rate", f"{approval_rate:.0f}%", f"{approved} executed / approved")
    with c3: metric("Needs review", str(review), "Sample queue" if demo_mode else "Awaiting execution / reconciliation")
    with c4: metric("Decision latency", "—", "Run the benchmark to measure")

    st.markdown("### Activity")
    left, right = st.columns([1.65, 1])
    with left:
        if trades.empty:
            st.info("No trades have been recorded yet.")
        else:
            display = trades.copy()
            display["amount"] = display["amount"].map(lambda value: f"${value:,.0f}")
            display.columns = ["Time", "Counterparty", "Instrument", "Group", "Notional", "Decision"]
            st.dataframe(display, hide_index=True, use_container_width=True)
    with right:
        st.markdown("#### Exposure by product")
        if not trades.empty:
            chart = trades.groupby("group", as_index=False)["amount"].sum().set_index("group")
            st.bar_chart(chart, color="#0b6b4d", height=250)
        st.caption("Notional distribution across today's recorded decisions.")

elif page == "New trade":
    st.markdown("### Pre-trade check")
    st.markdown('<div class="callout">Atlas evaluates instrument eligibility and available limit before the order reaches execution.</div>', unsafe_allow_html=True)
    if not demo_mode:
        st.caption("Connected fixture: NORTHSTAR / EQUITY / AAPL / USD, initial capacity 100.00. External execution is a local simulator.")
        pending = st.session_state.get("pending_submission")
        if pending:
            st.warning("A previous submission may still be pending. Retry it to retrieve the same trade before creating another.")
            if st.button("Retry pending submission"):
                try:
                    result = api("POST", "/trade", headers={"Idempotency-Key": pending["key"]}, json=pending["body"])
                    st.json(result)
                    if result.get("status") in ("EXECUTED", "REJECTED"):
                        del st.session_state.pending_submission
                except requests.RequestException as exc:
                    submission_error(exc)
        trade_id = st.text_input("Trade reference to inspect")
        use_ai = st.checkbox("Add AI analysis across events", value=False,
            help="Requires a configured model. Event relations are checked; model interpretations remain hypotheses. All actions stay read-only.")
        if trade_id and st.button("Explain rejection or anomaly"):
            try:
                path = f"/trades/{quote(trade_id, safe='')}/explanation"
                explanation = api("GET", path, params={"mode": "ai" if use_ai else "rules"})
                source = {"rules": "Rule explanation", "model_ordered": "AI-organized, verified evidence",
                          "model_attributed": "Rule facts with AI cross-event interpretation",
                          "rules_fallback": "Rule explanation (model unavailable or output rejected)",
                          "fixture_ordered": "Test fixture", "fixture_attributed": "Test fixture"}.get(explanation.get("mode"), "Explanation")
                st.caption(source)
                if explanation.get("abstained"):
                    st.warning(explanation.get("summary", "Evidence is insufficient."))
                else:
                    st.info(explanation.get("summary", ""))
                for finding in explanation.get("findings", []):
                    st.write(finding.get("text", ""))
                    st.caption("Evidence: " + ", ".join(finding.get("evidenceIds", [])))
                for insight in explanation.get("attributions", []):
                    st.write("Observed relationship: " + insight.get("observedRelation", ""))
                    st.text("AI interpretation: " + insight.get("interpretation", ""))
                    st.text("Uncertainty: " + insight.get("uncertainty", ""))
                    st.caption("Evidence: " + ", ".join(insight.get("evidenceIds", [])))
                    st.caption(insight.get("validationScope", ""))
                for check in explanation.get("nextChecks", []):
                    st.write("Read-only check: " + check.get("text", ""))
                st.caption("Explanations cannot approve trades, change capacity or execute operations.")
                with st.expander("Evidence and provenance"):
                    st.json({key: explanation.get(key) for key in ["tradeVersion", "snapshotHash", "ruleVersion",
                        "promptVersion", "mode", "providerOutcome", "modelCalls", "evidence", "limitations"]})
            except requests.RequestException as exc:
                st.error(f"Explanation could not be loaded: {exc}")
        if trade_id and st.button("Refresh trade and events"):
            try:
                path = f"/trades/{quote(trade_id, safe='')}"
                st.json(api("GET", path))
                st.json(api("GET", path + "/events"))
            except requests.RequestException as exc:
                st.error(f"Trade could not be loaded: {exc}")
    with st.form("trade_form"):
        a, b, c = st.columns(3)
        with a:
            counterparty = st.selectbox("Counterparty", (["NORTHSTAR", "MERIDIAN", "APEX"] if demo_mode else ["NORTHSTAR"]))
            instrument_group = st.selectbox("Product group", (["EQUITY", "BOND", "FX", "COMMODITY"] if demo_mode else ["EQUITY"]))
        with b:
            instrument = st.text_input("Instrument", "AAPL")
            amount = st.number_input("Notional (USD)", min_value=0.01, max_value=999_999_999.99, value=500_000.0 if demo_mode else 10.0, step=10.0)
        with c:
            country = st.selectbox("Risk country", (["US", "GB", "SG", "JP"] if demo_mode else ["US"]))
            exchange = st.selectbox("Venue", (["NASDAQ", "NYSE", "LSE", "SGX"] if demo_mode else ["NASDAQ"]))
        confirmed = st.checkbox("I confirm this trade submission", value=False)
        submitted = st.form_submit_button("Run checks & submit", use_container_width=True)

    if submitted:
        request = {
            "instrumentGroup": instrument_group, "instrument": instrument,
            "settlementCurrency": "USD", "tradeCurrency": "USD",
            "country": country, "exchange": exchange, "department": "GLOBAL_MARKETS",
        }
        if demo_mode:
            available = DEMO_LIMITS.get((counterparty, instrument_group), (10_000_000, "USD"))[0]
            status = "APPROVED" if amount <= available else "REVIEW"
            st.session_state.demo_trades.insert(0, {
                "time": datetime.now().strftime("%H:%M:%S"), "counterparty": counterparty,
                "instrument": instrument, "group": instrument_group, "amount": amount, "status": status,
            })
            if status == "APPROVED":
                st.success(f"Approved · ${amount:,.0f} reserved against a ${available:,.0f} available limit.")
            else:
                st.warning("Routed to manual review · requested notional exceeds the available limit.")
        else:
            try:
                if not confirmed:
                    st.warning("Confirm the trade before submitting.")
                    st.stop()
                if st.session_state.get("pending_submission"):
                    st.warning("Resolve the previous submission using Retry pending submission first.")
                    st.stop()
                body = {"instrumentVerificationRequest": request, "counterparty": counterparty,
                        "amount": format(Decimal(str(amount)), ".2f"), "confirmed": True}
                pending = {"key": str(uuid.uuid4()), "body": body}
                st.session_state.pending_submission = pending
                result = api("POST", "/trade", headers={"Idempotency-Key": pending["key"]}, json=body)
                st.json(result)
                if result.get("status") in ("EXECUTED", "REJECTED"):
                    del st.session_state.pending_submission
                    st.success(result.get("message", "Decision recorded"))
                else:
                    st.info("Execution is pending. The reservation remains held until the outcome is known.")
            except requests.RequestException as exc:
                submission_error(exc)

elif page == "Limit explorer":
    st.markdown("### Counterparty capacity")
    x, y, z = st.columns([1, 1, .6])
    with x: counterparty = st.selectbox("Counterparty", (["NORTHSTAR", "MERIDIAN", "APEX"] if demo_mode else ["NORTHSTAR"]))
    with y: instrument_group = st.selectbox("Product group", (["EQUITY", "BOND", "FX", "COMMODITY"] if demo_mode else ["EQUITY"]))
    with z:
        st.write("")
        st.write("")
        lookup = st.button("Check limit", use_container_width=True)
    if lookup:
        if demo_mode:
            available, currency = DEMO_LIMITS.get((counterparty, instrument_group), (10_000_000, "USD"))
            st.metric("Available capacity", f"{currency} {available:,.0f}", "Refreshed just now")
            st.progress(min(available / 40_000_000, 1.0), text="Remaining against desk ceiling")
        else:
            try:
                result = api("GET", f"/limit/{quote(counterparty, safe='')}/{quote(instrument_group, safe='')}?currency=USD")
                st.metric("Available capacity", f"{result.get('currency', 'USD')} {float(result.get('availableLimit', 0)):,.2f}")
            except requests.RequestException as exc:
                st.error(f"Limit could not be loaded: {exc}")

else:
    st.markdown("### Exception queue")
    approvals = pd.DataFrame([
        {"age": "04m", "instrument": "EURUSD", "reason": "Limit exception", "owner": "FX Risk", "priority": "High"},
        {"age": "18m", "instrument": "NVDA", "reason": "New instrument", "owner": "Product Control", "priority": "Normal"},
        {"age": "31m", "instrument": "JP10Y", "reason": "Venue mismatch", "owner": "Rates Risk", "priority": "Normal"},
    ])
    if not demo_mode:
        st.info("The current backend exposes approval creation; queue management is shown here as the next API milestone.")
    if not demo_mode:
        st.stop()
    st.dataframe(approvals.rename(columns={"age":"Age", "instrument":"Instrument", "reason":"Reason", "owner":"Owner", "priority":"Priority"}), hide_index=True, use_container_width=True)
    st.caption(f"Queue snapshot · {(datetime.now() - timedelta(minutes=1)).strftime('%d %b %Y, %H:%M')}")
