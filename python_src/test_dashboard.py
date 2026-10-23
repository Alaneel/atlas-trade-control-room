"""UI behavior checks using Streamlit's public AppTest interface."""

from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import requests
from streamlit.testing.v1 import AppTest

from demo_state import DEMO_LIMITS


APP = Path(__file__).with_name("trading_dashboard.py")


class DashboardTest(unittest.TestCase):
    def start(self):
        with patch.dict("os.environ", {"DEMO_MODE": "true"}):
            app = AppTest.from_file(str(APP)).run(timeout=15)
        self.assertFalse(app.exception)
        return app

    def test_all_demo_pages_render(self):
        app = self.start()
        for page in ["New trade", "Limit explorer", "Approvals", "Overview"]:
            app.radio[0].set_value(page).run()
            self.assertFalse(app.exception, page)

    def test_submit_reserves_once_and_reset_restores_capacity(self):
        app = self.start()
        app.radio[0].set_value("New trade").run()
        next(button for button in app.button if button.label == "Run checks & submit").click().run()
        self.assertFalse(app.exception)
        self.assertEqual(DEMO_LIMITS[("NORTHSTAR", "EQUITY")] - 500_000,
                         app.session_state["atlas_demo"]["limits"][("NORTHSTAR", "EQUITY")])
        app.radio[0].set_value("Overview").run()
        self.assertEqual(6, len(app.session_state["atlas_demo"]["trades"]))
        next(button for button in app.button if button.label == "Reset demo").click().run()
        self.assertEqual(DEMO_LIMITS, app.session_state["atlas_demo"]["limits"])
        self.assertEqual(5, len(app.session_state["atlas_demo"]["trades"]))

    def test_connected_execution_status_is_counted_without_sample_metrics(self):
        response = Mock()
        response.json.return_value = [{
            "timestamp": "2026-10-22T09:00:00", "counterparty": "TEST", "amount": 1000,
            "status": "EXECUTED", "instrumentVerificationRequest": {"instrument": "TEST", "instrumentGroup": "Bonds"},
        }]
        with patch.dict("os.environ", {"DEMO_MODE": "false"}), \
                patch("requests.get", return_value=Mock(ok=True)), \
                patch("requests.request", return_value=response):
            app = AppTest.from_file(str(APP)).run(timeout=15)
        self.assertFalse(app.exception)
        rendered = " ".join(item.value for item in app.markdown)
        self.assertIn("100%", rendered)
        self.assertNotIn("84 ms", rendered)
        self.assertNotIn("Within desk SLA", rendered)
        self.assertNotIn("+8.2%", rendered)

    def test_connected_rejection_is_not_rendered_as_success(self):
        history = Mock()
        history.json.return_value = []
        rejection = Mock(status_code=200)
        rejection.json.return_value = {"status": "REJECTED", "message": "Insufficient limit"}

        def request(method, url, **kwargs):
            return rejection if method == "POST" else history

        with patch.dict("os.environ", {"DEMO_MODE": "false"}), \
                patch("requests.get", return_value=Mock(ok=True)), \
                patch("requests.request", side_effect=request) as calls:
            app = AppTest.from_file(str(APP)).run(timeout=15)
            app.radio[0].set_value("New trade").run()
            next(button for button in app.button if button.label == "Run checks & submit").click().run()
        self.assertFalse(app.exception)
        self.assertTrue(any("REJECTED" in item.value for item in app.warning))
        self.assertFalse(app.success)
        posted = next(call.kwargs["json"] for call in calls.call_args_list if call.args[0] == "POST")
        self.assertEqual("Abbey National Treasury Services PLC", posted["counterparty"])
        self.assertEqual("RE", posted["instrumentVerificationRequest"]["department"])
        self.assertEqual("CHF", posted["instrumentVerificationRequest"]["tradeCurrency"])

    def test_connected_validation_preserves_field_error_message(self):
        history = Mock()
        history.json.return_value = []
        invalid = requests.Response()
        invalid.status_code = 400
        invalid._content = b'{"message":"Input validation failed","details":["counterparty: must not be blank"]}'

        def request(method, url, **kwargs):
            return invalid if method == "POST" else history

        with patch.dict("os.environ", {"DEMO_MODE": "false"}), \
                patch("requests.get", return_value=Mock(ok=True)), \
                patch("requests.request", side_effect=request):
            app = AppTest.from_file(str(APP)).run(timeout=15)
            app.radio[0].set_value("New trade").run()
            next(button for button in app.button if button.label == "Run checks & submit").click().run()
        self.assertFalse(app.exception)
        self.assertTrue(any("counterparty: must not be blank" in item.value for item in app.error))

    def test_timeout_and_pending_retry_keep_the_same_key_and_exact_payload(self):
        history = Mock(status_code=200)
        history.json.return_value = []
        pending = Mock(status_code=202)
        pending.json.return_value = {"status": "IN_PROGRESS", "message": "Manual reconciliation required; do not start another intent."}
        complete = Mock(status_code=200)
        complete.json.return_value = {"status": "EXECUTED", "message": "Recorded"}
        posts = []

        def request(method, url, **kwargs):
            if method != "POST":
                return history
            posts.append(kwargs)
            if len(posts) == 1:
                raise requests.Timeout("Response timed out")
            return pending if len(posts) == 2 else complete

        with patch.dict("os.environ", {"DEMO_MODE": "false"}), \
                patch("requests.get", return_value=Mock(ok=True)), \
                patch("requests.request", side_effect=request):
            app = AppTest.from_file(str(APP)).run(timeout=15)
            app.radio[0].set_value("New trade").run()
            # Forms send their edited values with the submit event. Running the
            # script between typing and submit discards unsubmitted form edits
            # in newer AppTest versions, unlike a browser's batched form event.
            next(field for field in app.text_input if field.label == "Notional in the counterparty limit currency").set_value("0.100000000")
            next(button for button in app.button if button.label == "Run checks & submit").click().run()
            self.assertEqual("pending", app.session_state["api_trade_intent"]["state"])
            next(button for button in app.button if button.label == "Retry same trade").click().run()
            self.assertEqual("pending", app.session_state["api_trade_intent"]["state"])
            self.assertFalse(app.success)
            self.assertTrue(any("Manual reconciliation required" in item.value for item in app.info))
            next(button for button in app.button if button.label == "Retry same trade").click().run()
            self.assertEqual("complete", app.session_state["api_trade_intent"]["state"])
            self.assertTrue(app.success)
        self.assertFalse(app.exception)
        self.assertEqual(3, len(posts))
        self.assertEqual(1, len({post["headers"]["Idempotency-Key"] for post in posts}))
        self.assertTrue(all(post["json"]["amount"] == "0.1" for post in posts))
        self.assertEqual(posts[0]["json"], posts[2]["json"])

    def test_changed_form_and_explicit_new_intent_rotate_identity(self):
        response = Mock(status_code=200)
        response.json.return_value = {"status": "EXECUTED", "message": "Recorded"}
        history = Mock(status_code=200)
        history.json.return_value = []
        posts = []

        def request(method, url, **kwargs):
            if method != "POST":
                return history
            posts.append(kwargs)
            return response

        with patch.dict("os.environ", {"DEMO_MODE": "false"}), \
                patch("requests.get", return_value=Mock(ok=True)), \
                patch("requests.request", side_effect=request):
            app = AppTest.from_file(str(APP)).run(timeout=15)
            app.radio[0].set_value("New trade").run()
            next(button for button in app.button if button.label == "Run checks & submit").click().run()
            next(button for button in app.button if button.label == "Run checks & submit").click().run()
            # Batch this edit with the form submit, matching browser behavior.
            next(field for field in app.text_input if field.label == "Notional in the counterparty limit currency").set_value("0.00000001")
            next(button for button in app.button if button.label == "Run checks & submit").click().run()
            next(button for button in app.button if button.label == "Start a new trade intent").click().run()
            self.assertIsNone(app.session_state["api_trade_intent"])
            next(button for button in app.button if button.label == "Run checks & submit").click().run()
        self.assertFalse(app.exception)
        keys = [post["headers"]["Idempotency-Key"] for post in posts]
        self.assertEqual(keys[0], keys[1])
        self.assertNotEqual(keys[1], keys[2])
        self.assertNotEqual(keys[2], keys[3])
        self.assertEqual("0.00000001", posts[2]["json"]["amount"])

    def test_full_precision_api_history_renders_without_float_rounding(self):
        response = requests.Response()
        response.status_code = 200
        response._content = b'[{"amount":99999999999999999999999999.12345678,"status":"EXECUTED"}]'
        with patch.dict("os.environ", {"DEMO_MODE": "false"}), \
                patch("requests.get", return_value=Mock(ok=True)), \
                patch("requests.request", return_value=response):
            app = AppTest.from_file(str(APP)).run(timeout=15)
        self.assertFalse(app.exception)
        rendered = " ".join(item.value for item in app.markdown)
        self.assertIn("99,999,999,999,999,999,999,999,999.12345678", rendered)
        self.assertIn("99,999,999,999,999,999,999,999,999.12345678", app.dataframe[0].value.iloc[0]["Notional"])

    def test_uncertain_api_responses_keep_identity_until_a_confirmed_decision(self):
        for content in [b"invalid proxy response", b"[]", b"{}", b'{"status":"UNKNOWN"}']:
            with self.subTest(content=content):
                history = Mock(status_code=200)
                history.json.return_value = []
                invalid = requests.Response()
                invalid.status_code = 200
                invalid._content = content
                complete = Mock(status_code=200)
                complete.json.return_value = {"status": "EXECUTED", "message": "Recorded"}
                posts = []

                def request(method, url, **kwargs):
                    if method != "POST":
                        return history
                    posts.append(kwargs)
                    return invalid if len(posts) == 1 else complete

                with patch.dict("os.environ", {"DEMO_MODE": "false"}), \
                        patch("requests.get", return_value=Mock(ok=True)), \
                        patch("requests.request", side_effect=request):
                    app = AppTest.from_file(str(APP)).run(timeout=15)
                    app.radio[0].set_value("New trade").run()
                    next(button for button in app.button if button.label == "Run checks & submit").click().run()
                    self.assertFalse(app.exception)
                    self.assertEqual("pending", app.session_state["api_trade_intent"]["state"])
                    self.assertTrue(app.session_state["api_trade_intent"]["error"])
                    self.assertFalse(app.success)
                    next(button for button in app.button if button.label == "Retry same trade").click().run()
                    self.assertFalse(app.exception)
                    self.assertEqual("complete", app.session_state["api_trade_intent"]["state"])
                self.assertEqual(posts[0]["headers"], posts[1]["headers"])
                self.assertEqual(posts[0]["json"], posts[1]["json"])


if __name__ == "__main__":
    unittest.main()
