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
        rejection = Mock()
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


if __name__ == "__main__":
    unittest.main()
