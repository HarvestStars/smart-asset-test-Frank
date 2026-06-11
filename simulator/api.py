from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


class SmartAssetApiError(RuntimeError):
    pass


def quarter_end(start: datetime) -> datetime:
    return start + timedelta(minutes=15)


def iso_local(value: datetime) -> str:
    return value.replace(microsecond=0).isoformat()


@dataclass(frozen=True)
class Order:
    quarter: datetime
    side: str
    quantity: Decimal
    price: Decimal

    def payload(self) -> dict[str, str]:
        return {
            "delivery_start_time": iso_local(self.quarter),
            "delivery_end_time": iso_local(quarter_end(self.quarter)),
            "order_side": self.side,
            "quantity": str(self.quantity),
            "price": str(self.price),
        }


class SmartAssetClient:
    def __init__(self, base_url: str = "http://localhost:8080", timeout: float = 5.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def health(self) -> dict[str, Any]:
        return self._request("GET", "/")

    def post_order(self, order: Order) -> dict[str, Any]:
        return self._request("POST", "/api/orderupdate", order.payload())

    def overview(self) -> list[dict[str, Any]]:
        return self._request("GET", "/api/overview")

    def orderbook(self, quarter: datetime) -> dict[str, Any] | None:
        query = urlencode({"deliveryStartTime": iso_local(quarter)})
        try:
            return self._request("GET", f"/api/orderbook?{query}")
        except SmartAssetApiError as exc:
            if "HTTP 404" in str(exc):
                return None
            raise

    def sourcing_cost(self) -> dict[str, Any]:
        return self._request("GET", "/api/sourcing-cost")

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
    ) -> Any:
        data = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = Request(
            f"{self.base_url}{path}",
            data=data,
            headers=headers,
            method=method,
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                body = response.read()
                return json.loads(body) if body else None
        except HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise SmartAssetApiError(
                f"{method} {path} failed with HTTP {exc.code}: {detail}"
            ) from exc
        except URLError as exc:
            raise SmartAssetApiError(
                f"Cannot reach Kotlin service at {self.base_url}: {exc.reason}"
            ) from exc
