from __future__ import annotations

import json
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

from .api import SmartAssetApiError, SmartAssetClient, iso_local


def _html(quarters: list[datetime], refresh_seconds: float) -> str:
    quarter_json = json.dumps([iso_local(q) for q in quarters])
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Smart Asset Order Book Monitor</title>
  <style>
    :root {{ color-scheme: dark; font-family: Inter, system-ui, sans-serif; }}
    body {{ margin: 0; background: #0b1020; color: #e5e7eb; }}
    header {{ padding: 18px 24px; border-bottom: 1px solid #26314d; }}
    h1 {{ margin: 0 0 6px; font-size: 22px; }}
    #status {{ color: #94a3b8; font-size: 13px; }}
    main {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
            gap: 18px; padding: 18px; }}
    .book {{ background: #11182b; border: 1px solid #26314d; border-radius: 10px;
             overflow: hidden; }}
    .book h2 {{ margin: 0; padding: 14px 16px; font-size: 16px;
                background: #151f36; }}
    .spread {{ padding: 10px 16px; color: #cbd5e1; border-bottom: 1px solid #26314d; }}
    .columns {{ display: grid; grid-template-columns: 1fr 1fr; }}
    .side {{ padding: 12px; }}
    .side + .side {{ border-left: 1px solid #26314d; }}
    h3 {{ margin: 0 0 8px; font-size: 13px; text-transform: uppercase; }}
    .bids h3 {{ color: #4ade80; }} .asks h3 {{ color: #fb7185; }}
    table {{ width: 100%; border-collapse: collapse; font-variant-numeric: tabular-nums; }}
    th, td {{ text-align: right; padding: 6px; border-bottom: 1px solid #202a43; }}
    th {{ color: #94a3b8; font-size: 11px; }} td {{ font-size: 13px; }}
    .empty {{ color: #64748b; padding: 16px 0; }}
    @media (max-width: 850px) {{ main {{ grid-template-columns: 1fr; }} }}
  </style>
</head>
<body>
  <header>
    <h1>Smart Asset Order Book Monitor</h1>
    <div id="status">Starting...</div>
  </header>
  <main id="books"></main>
  <script>
    const quarters = {quarter_json};
    const refreshMs = {int(refresh_seconds * 1000)};

    const rows = (orders) => orders.length
      ? `<table><thead><tr><th>Price</th><th>Qty MWh</th><th>Order</th></tr></thead>
         <tbody>${{orders.map(o => `<tr><td>${{o.price}}</td><td>${{o.quantity}}</td>
         <td title="${{o.orderId}}">${{(o.orderId || "").slice(0, 8)}}</td></tr>`).join("")}}</tbody></table>`
      : `<div class="empty">No resting orders</div>`;

    function render(book, quarter) {{
      if (!book) return `<section class="book"><h2>${{quarter}}</h2>
        <div class="empty" style="padding:16px">No order book yet</div></section>`;
      const bid = book.bids?.[0]?.price;
      const ask = book.asks?.[0]?.price;
      const spread = bid != null && ask != null ? (Number(ask) - Number(bid)).toFixed(2) : "n/a";
      return `<section class="book"><h2>${{quarter}}</h2>
        <div class="spread">Best bid: ${{bid ?? "n/a"}} | Best ask: ${{ask ?? "n/a"}} | Spread: ${{spread}}</div>
        <div class="columns">
          <div class="side bids"><h3>Bids</h3>${{rows(book.bids || [])}}</div>
          <div class="side asks"><h3>Asks</h3>${{rows(book.asks || [])}}</div>
        </div></section>`;
    }}

    async function refresh() {{
      try {{
        const response = await fetch("/data", {{cache: "no-store"}});
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || response.statusText);
        document.getElementById("books").innerHTML =
          data.books.map((book, i) => render(book, quarters[i])).join("");
        document.getElementById("status").textContent =
          `Updated ${{new Date().toLocaleTimeString()}} | refresh ${{refreshMs / 1000}}s`;
      }} catch (error) {{
        document.getElementById("status").textContent = `Error: ${{error.message}}`;
      }}
    }}
    refresh();
    setInterval(refresh, refreshMs);
  </script>
</body>
</html>"""


def run_dashboard(
    client: SmartAssetClient,
    quarters: list[datetime],
    host: str = "127.0.0.1",
    port: int = 8090,
    refresh_seconds: float = 1.0,
) -> None:
    page = _html(quarters, refresh_seconds).encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/":
                self._send(200, "text/html; charset=utf-8", page)
                return
            if path == "/data":
                try:
                    books = [client.orderbook(quarter) for quarter in quarters]
                    self._json(200, {"books": books})
                except SmartAssetApiError as exc:
                    self._json(502, {"error": str(exc)})
                return
            self._json(404, {"error": "not found"})

        def log_message(self, format: str, *args: object) -> None:
            return

        def _json(self, status: int, value: object) -> None:
            self._send(
                status,
                "application/json",
                json.dumps(value).encode("utf-8"),
            )

        def _send(self, status: int, content_type: str, body: bytes) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer((host, port), Handler)
    print(f"[dashboard] http://{host}:{port}")
    print("[dashboard] Ctrl+C to stop")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
