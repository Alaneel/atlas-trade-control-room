from decimal import Decimal, localcontext
import unittest

from demo_state import new_demo_state, submit_demo_trade
from money import format_money, money_string, parse_money, total_money
from trade_intent import prepare_intent


class DecimalMoneyTest(unittest.TestCase):
    def test_full_decimal128_boundary_is_preserved_without_context_rounding(self):
        amount = "99999999999999999999999999.12345678"
        with localcontext() as context:
            context.prec = 8
            self.assertEqual(amount, money_string(amount))
        self.assertEqual("1", money_string("1.000000000"))
        self.assertEqual("0.00000001", money_string("0.000000010000"))

    def test_invalid_amounts_are_rejected_without_rounding(self):
        for amount in ["100000000000000000000000000", "0.000000001", "1.123456789",
                       "NaN", "Infinity", "-Infinity", "0", "-1", "", None, True]:
            with self.subTest(amount=amount), self.assertRaises(ValueError):
                parse_money(amount)

    def test_decimal_reservations_and_history_are_exact(self):
        state = new_demo_state()
        key = ("NORTHSTAR", "EQUITY")
        state["limits"][key] = Decimal("0.3")
        for amount in ["0.1", "0.2"]:
            self.assertEqual("APPROVED", submit_demo_trade(state, *key, "AAPL", amount)[0])
        self.assertEqual(Decimal("0.0"), state["limits"][key])
        self.assertIsInstance(state["trades"][0]["amount"], Decimal)
        self.assertEqual("REVIEW", submit_demo_trade(state, *key, "AAPL", "0.00000001")[0])

    def test_display_and_totals_do_not_lose_fractional_digits(self):
        amount = Decimal("99999999999999999999999999.12345678")
        total = total_money([amount, amount])
        self.assertEqual("199999999999999999999999998.24691356", format(total, "f"))
        self.assertEqual("199,999,999,999,999,999,999,999,998.24691356", format_money(total))


class TradeIntentTest(unittest.TestCase):
    def test_same_payload_reuses_identity_but_changed_payload_rotates(self):
        payload = {"amount": "0.1", "counterparty": "TEST"}
        first = prepare_intent(None, payload)
        self.assertIs(first, prepare_intent(first, dict(payload)))
        changed = prepare_intent(first, {**payload, "amount": "0.2"})
        self.assertNotEqual(first["key"], changed["key"])
        fresh = prepare_intent(None, payload)
        self.assertNotEqual(first["key"], fresh["key"])

    def test_payload_is_frozen_for_retries(self):
        payload = {"amount": "0.1", "instrumentVerificationRequest": {"instrument": "AAPL"}}
        intent = prepare_intent(None, payload)
        payload["instrumentVerificationRequest"]["instrument"] = "MSFT"
        self.assertEqual("AAPL", intent["payload"]["instrumentVerificationRequest"]["instrument"])


if __name__ == "__main__":
    unittest.main()
