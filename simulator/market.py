from __future__ import annotations

import random
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

from .api import Order, SmartAssetClient


@dataclass
class QuarterMarket:
    quarter: datetime
    fair_price: float
    anchor_price: float


class RandomMarketGenerator:
    """Produces a slow, mostly non-crossing two-quarter market data stream."""

    def __init__(
        self,
        client: SmartAssetClient,
        quarters: list[datetime],
        interval_seconds: float = 1.5,
        seed: int | None = None,
    ):
        if len(quarters) != 2:
            raise ValueError("RandomMarketGenerator requires exactly two quarters")
        self.client = client
        self.interval_seconds = interval_seconds
        self.random = random.Random(seed)
        base = self.random.uniform(55.0, 75.0)
        self.markets = [
            QuarterMarket(quarters[0], base, base),
            QuarterMarket(quarters[1], base + self.random.uniform(-3.0, 3.0), base),
        ]

    def seed_books(self, levels_per_side: int = 3) -> None:
        """Adds initial bid/ask depth around each quarter's fair price."""
        for market in self.markets:
            for level in range(levels_per_side, 0, -1):
                bid = market.fair_price - 0.8 - level * 0.45
                self._submit(market.quarter, "BUY", bid, self._quantity())
            for level in range(1, levels_per_side + 1):
                ask = market.fair_price + 0.8 + level * 0.45
                self._submit(market.quarter, "SELL", ask, self._quantity())

    def run(
        self,
        stop_event: threading.Event | None = None,
        max_orders: int | None = None,
    ) -> None:
        stop_event = stop_event or threading.Event()
        count = 0
        while not stop_event.is_set() and (max_orders is None or count < max_orders):
            market = self.random.choice(self.markets)
            self._move_fair_price(market)

            side = self.random.choice(("BUY", "SELL"))
            price = self._next_price(market, side)
            order = self._submit(market.quarter, side, price, self._quantity())
            count += 1
            print(
                f"[market] {order.side:<4} {order.quantity} MWh @ {order.price} "
                f"quarter={order.quarter.isoformat(timespec='minutes')}"
            )
            stop_event.wait(self.interval_seconds)

    def _move_fair_price(self, market: QuarterMarket) -> None:
        # Small random walk plus mean reversion prevents unlimited price drift.
        shock = self.random.gauss(0.0, 0.22)
        reversion = (market.anchor_price - market.fair_price) * 0.03
        market.fair_price = max(5.0, market.fair_price + shock + reversion)

    def _next_price(self, market: QuarterMarket, side: str) -> float:
        half_spread = self.random.uniform(0.6, 1.4)
        depth = abs(self.random.gauss(0.35, 0.35))

        # A small fraction of aggressive orders cross the fair spread and create
        # actual trades; most orders add plausible resting depth.
        aggressive = self.random.random() < 0.08
        if side == "BUY":
            price = (
                market.fair_price + half_spread
                if aggressive
                else market.fair_price - half_spread - depth
            )
        else:
            price = (
                market.fair_price - half_spread
                if aggressive
                else market.fair_price + half_spread + depth
            )
        return round(max(0.01, price), 2)

    def _quantity(self) -> float:
        # Small quarter-energy orders with occasional larger liquidity.
        return round(self.random.triangular(0.05, 1.5, 0.35), 3)

    def _submit(
        self,
        quarter: datetime,
        side: str,
        price: float,
        quantity: float,
    ) -> Order:
        order = Order(
            quarter=quarter,
            side=side,
            quantity=Decimal(str(quantity)),
            price=Decimal(str(price)),
        )
        self.client.post_order(order)
        return order
