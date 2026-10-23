"""Exact decimal amounts shared by the demo and API client.

The 26 integer / 8 fractional digit boundary matches the backend Decimal128
contract. Trailing zeroes are ignored, never rounded away.
"""

from decimal import Decimal, InvalidOperation, localcontext


def parse_money(value, *, allow_zero=False):
    if isinstance(value, bool) or value is None:
        raise ValueError("Notional must be a finite decimal greater than zero.")
    try:
        amount = value if isinstance(value, Decimal) else Decimal(str(value).strip())
    except (InvalidOperation, TypeError, ValueError):
        raise ValueError("Enter a decimal amount, such as 500000 or 0.10.") from None
    if not amount.is_finite() or amount < 0 or (amount == 0 and not allow_zero):
        raise ValueError("Notional must be a finite decimal greater than zero.")

    sign, digits, exponent = amount.as_tuple()
    digits = list(digits)
    if amount == 0:
        return Decimal(0)
    while digits and digits[-1] == 0:
        digits.pop()
        exponent += 1
    if max(len(digits) + exponent, 0) > 26 or max(-exponent, 0) > 8:
        raise ValueError("Use at most 26 integer digits and 8 fractional digits; amounts are never rounded.")
    return Decimal((sign, tuple(digits), exponent))


def money_string(value, *, allow_zero=False):
    return format(parse_money(value, allow_zero=allow_zero), "f")


def format_money(value):
    # Aggregated totals may exceed the per-trade bound. Formatting a Decimal
    # does not apply the ambient precision and must preserve every stored digit.
    amount = value if isinstance(value, Decimal) else parse_money(value, allow_zero=True)
    if not amount.is_finite() or amount < 0:
        raise ValueError("Cannot display a nonfinite or negative monetary value.")
    return format(amount, ",f")


def subtract_money(balance, amount):
    with localcontext() as context:
        context.prec = 80
        return parse_money(balance, allow_zero=True) - parse_money(amount)


def total_money(values):
    with localcontext() as context:
        context.prec = 80
        return sum((parse_money(value, allow_zero=True) for value in values), Decimal(0))
