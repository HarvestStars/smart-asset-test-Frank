from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal
from typing import Callable

from .api import Order, SmartAssetClient


def _isolated_quarters() -> tuple[datetime, datetime]:
    # Noon is outside every configured charging window, so the optimizer does
    # not consume these orders while matching-engine behavior is being tested.
    # The timestamp-derived day offset makes repeated runs use fresh book keys.
    day_offset = 30 + int(datetime.now().timestamp()) % 10_000
    day = datetime.now().date() + timedelta(days=day_offset)
    first = datetime.combine(day, datetime.min.time()).replace(hour=12)
    return first, first + timedelta(minutes=15)


def _post(
    client: SmartAssetClient,
    quarter: datetime,
    side: str,
    quantity: str,
    price: str,
) -> None:
    client.post_order(
        Order(
            quarter=quarter,
            side=side,
            quantity=Decimal(quantity),
            price=Decimal(price),
        )
    )


def book_shape(client: SmartAssetClient) -> None:
    """Verifies quarter isolation, best-first sorting and overview values."""
    q1, q2 = _isolated_quarters()
    _post(client, q1, "BUY", "0.4", "48")
    _post(client, q1, "BUY", "0.6", "50")
    _post(client, q1, "SELL", "0.5", "53")
    _post(client, q1, "SELL", "0.7", "52")
    _post(client, q2, "BUY", "0.3", "70")
    _post(client, q2, "SELL", "0.3", "74")

    first = client.orderbook(q1)
    second = client.orderbook(q2)
    assert first is not None and second is not None
    assert [Decimal(str(o["price"])) for o in first["bids"][:2]] == [
        Decimal("50"),
        Decimal("48"),
    ]
    assert [Decimal(str(o["price"])) for o in first["asks"][:2]] == [
        Decimal("52"),
        Decimal("53"),
    ]
    assert len(second["bids"]) == 1 and len(second["asks"]) == 1
    print("[PASS] book-shape: sorting, spread and quarter isolation")


def partial_match(client: SmartAssetClient) -> None:
    """Verifies that an aggressive order partially reduces resting liquidity."""
    q1, _ = _isolated_quarters()
    q1 += timedelta(days=1)
    _post(client, q1, "BUY", "1.0", "50")
    _post(client, q1, "SELL", "0.4", "50")

    book = client.orderbook(q1)
    assert book is not None
    assert len(book["asks"]) == 0
    assert Decimal(str(book["bids"][0]["quantity"])) == Decimal("0.6")
    print("[PASS] partial-match: 1.0 bid reduced to 0.6 after 0.4 sell")


def optimizer_fillup(client: SmartAssetClient) -> None:
    """Checks that fresh ask liquidity can trigger an optimizer purchase.

    Run this scenario against a freshly started Kotlin service. Existing positions
    or an already populated market_orders.jsonl can make the result ambiguous.
    """
    before = client.sourcing_cost()
    day = datetime.now().date() + timedelta(days=1)
    quarter = datetime.combine(day, datetime.min.time()).replace(hour=1)
    _post(client, quarter, "SELL", "0.25", "45")
    after = client.sourcing_cost()

    before_count = int(before["buy_order_count"])
    after_count = int(after["buy_order_count"])
    assert after_count > before_count, (
        "Optimizer did not add a BUY audit record. Use a fresh application state "
        "and ensure this quarter overlaps a configured charging group."
    )
    print(
        "[PASS] optimizer-fillup: BUY audit count "
        f"{before_count} -> {after_count}"
    )


SCENARIOS: dict[str, Callable[[SmartAssetClient], None]] = {
    "book-shape": book_shape,
    "partial-match": partial_match,
    "optimizer-fillup": optimizer_fillup,
}


def run_scenario(client: SmartAssetClient, name: str) -> None:
    SCENARIOS[name](client)
