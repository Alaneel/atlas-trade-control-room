import unittest

from demo_state import DEMO_LIMITS, new_demo_state, submit_demo_trade


class DemoDecisionsTest(unittest.TestCase):
    def test_reservations_reduce_capacity_and_do_not_overspend(self):
        state = new_demo_state()
        key = ("NORTHSTAR", "EQUITY")
        opening = state["limits"][key]
        self.assertEqual("APPROVED", submit_demo_trade(state, *key, "AAPL", opening)[0])
        self.assertEqual(0, state["limits"][key])
        self.assertEqual("REVIEW", submit_demo_trade(state, *key, "AAPL", 1_000)[0])
        self.assertEqual(0, state["limits"][key])

    def test_unknown_limits_are_not_invented(self):
        state = new_demo_state()
        self.assertEqual("DECLINED", submit_demo_trade(state, "MERIDIAN", "EQUITY", "AAPL", 1_000)[0])
        self.assertNotIn(("MERIDIAN", "EQUITY"), state["limits"])

    def test_unknown_instrument_is_reviewed_without_reserving(self):
        state = new_demo_state()
        before = state["limits"].copy()
        self.assertEqual("REVIEW", submit_demo_trade(state, "NORTHSTAR", "EQUITY", "UNKNOWN", 1_000)[0])
        self.assertEqual(before, state["limits"])

    def test_invalid_amounts_leave_state_unchanged(self):
        for amount in [0, -1, float("nan"), float("inf"), -float("inf"), None]:
            with self.subTest(amount=amount):
                state = new_demo_state()
                before = new_demo_state()
                with self.assertRaises(ValueError):
                    submit_demo_trade(state, "NORTHSTAR", "EQUITY", "AAPL", amount)
                self.assertEqual(before, state)

    def test_new_sessions_and_reset_are_isolated(self):
        a, b = new_demo_state(), new_demo_state()
        submit_demo_trade(a, "NORTHSTAR", "EQUITY", "AAPL", 1_000)
        a["trades"][1]["status"] = "CHANGED"
        self.assertEqual(DEMO_LIMITS, b["limits"])
        self.assertEqual("APPROVED", b["trades"][0]["status"])
        self.assertEqual(b, new_demo_state())


if __name__ == "__main__":
    unittest.main()
