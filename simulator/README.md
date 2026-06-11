# Smart Asset Simulator

Python 3.10+ tools for exercising the Kotlin order book, optimizer and position
allocation flow. The simulator uses only the Python standard library.

## Prerequisites

Start the Kotlin service first:

```powershell
.\gradlew.bat bootRun
```

The simulator defaults to `http://localhost:8080`.

## Random Two-Quarter Market

Run the generator and web dashboard together:

```powershell
python -m simulator all
```

Open:

```text
http://127.0.0.1:8090
```

By default, the simulator chooses the next two future 15-minute quarters,
creates three bid and ask levels per quarter, and submits one new order every
1.5 seconds. Most orders rest around a slowly moving fair price; a small number
are aggressive and cross the spread to create trades.

The Kotlin service currently has no cancel-order endpoint. Long simulator runs
therefore accumulate unmatched depth; restart the application for a clean book.
Choose quarters inside a configured charging window when you specifically want
to observe optimizer position changes.

Useful options:

```powershell
python -m simulator all --interval 2 --seed 42 --refresh 1

python -m simulator all `
  --quarter 2026-06-11T08:00:00 `
  --quarter 2026-06-11T08:15:00
```

The dashboard is served by Python and proxies reads to the Kotlin
`GET /api/orderbook` endpoint, avoiding browser cross-origin configuration.

The generator and dashboard can also run separately:

```powershell
python -m simulator generate --max-orders 50
python -m simulator dashboard
```

## Controlled Scenarios

Run these without the random generator so the order flow remains deterministic.

```powershell
python -m simulator scenario book-shape
```

Creates non-crossing books in two isolated future quarters and verifies:

- bids are sorted highest price first;
- asks are sorted lowest price first;
- orders remain isolated by delivery quarter.

```powershell
python -m simulator scenario partial-match
```

Places a `1.0 MWh @ 50` bid followed by a `0.4 MWh @ 50` sell and verifies that
the resting bid is reduced to `0.6 MWh`.

```powershell
python -m simulator scenario optimizer-fillup
```

Places future ask liquidity inside a configured charging window and verifies
that the optimizer adds a BUY record to `/api/sourcing-cost`.

Run `optimizer-fillup` against a freshly started application. Existing positions
or audit-log entries can make this stateful scenario ambiguous.

## Commands

```text
python -m simulator generate   # random orders only
python -m simulator dashboard  # web monitor only
python -m simulator all        # generator + monitor
python -m simulator scenario   # deterministic checks
```

Use `python -m simulator <command> --help` for all options.
