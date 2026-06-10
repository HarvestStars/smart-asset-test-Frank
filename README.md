# Smart Asset — EV Fleet Energy Optimizer

Automated energy procurement and EV charging dispatch for fleet operators.
Ingests live market order-book data, decides what to buy/sell and when, and
issues per-quarter charging commands to EV groups — all in a single
event-driven loop.

---

## Pipeline Architecture

Every market event flows through the same 5-layer pipeline.
The only branching point is inside the Optimizer (Fill-up vs Arbitrage).

```
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
  SMART ASSET PIPELINE
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  ① Market Input            ② Order Book                ③ Optimizer

  ┌─────────────────┐       ┌────────────────────────┐    ┌───────────────────────────┐
  │ REST API        │──────►│ OrderBookService       │───►│ OptimizationService       │
  │                 │       │                        │    │                           │
  │ POST /order     │       │ .bestAskPrice          │    │ @EventListener            │
  │ side: BUY/SELL  │       │ .bestBidPrice          │    │ onOrderBookUpdated()      │
  │ price           │       │ .bestAskQuantity       │    │                           │
  │ quantity        │       │ .bestBidQuantity       │    │ ── time advance ──        │
  │ deliveryStart   │       │ .getQuarterOverviews() │    │ expireBeforeTime(now)     │
  │ deliveryEnd     │       │ .getQuarterOrderBook() │    │ consumeChargedEnergy()    │
  └─────────────────┘       │ .getAllSellOrdersSorted│    │                           │
                            │                        │    │ ── stale-event guard ──   │
                  publishes │ OrderBookUpdatedEvent  │    │ if quarter < now → drop   │
                            │  .deliveryStartTime    │    │                           │
                            │  .fromOptimizer        │    │ ── phase select ──        │
                            └────────────────────────┘    │ pos < need → FILL-UP      │
                                                          │ pos ≥ need → ARBITRAGE    │
                                                          └───────────────────────────┘
                                                                        │
                  ┌─────────────────────────────────────────────────────┘
                  │
                  ▼
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  ④ Position & Need Management

  ┌────────────────────────┐         ┌──────────────────────────────┐
  │ PositionManager        │         │ ChargingNeedAggregator       │
  │                        │         │                              │
  │ positions: Map<        │         │ groups: List<ChargingGroup>  │
  │   LocalDateTime, MWh>  │         │   .name                      │
  │                        │         │   .startTime / endTime       │
  │ .getPosition(quarter)  │         │   .neededChargeMWh           │
  │ .adjustPosition(q, Δ)  │         │   .maxPowerMW                │
  │ .totalPosition()       │         │                              │
  │ .getAllPositions()     │         │ groupRemaining: Map<name,MWh>│
  │ .expireBeforeTime(now) │         │                              │
  │   └─ returns expired   │         │ .totalRemainingNeed()        │
  │      quarters          │         │ .maxBuyable(quarter)         │
  └────────────────────────┘         │ .softBuyable(q, remaining)   │
                                     │ .getGroupRemaining()         │
                                     │ .consumeChargedEnergy(map)   │
                                     └──────────────────────────────┘

- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  ⑤ Execution Clients

  ┌──────────────────────────────┐      ┌──────────────────────────────────┐
  │ MarketOrderClient            │      │ SteeringSignalDispatcher         │
  │                              │      │                                  │
  │ .placeBuy(q, end, qty, px)   │      │ lastSignals: Map<                │
  │ .placeSell(q, end, qty, px)  │      │   (group, quarter), Signal>      │
  │   ├─ calls OrderBookService  │      │                                  │
  │   │  processOrder()          │      │ .dispatch(positions)             │
  │   └─ appends to log          │      │   ├─ deriveSignals()             │
  │                              │      │   │    desire_g = min(           │
  │ .getAllOrders()              │      │   │      remaining_g,            │
  │   └─ reads jsonl log         │      │   │      maxPower×0.25h)         │
  │                              │      │   │    scale by pos if scarce    │
  │ market_orders.jsonl          │      │   └─ emitChanged()               │
  │   { orderId, side, qty, px,  │      │        zero-cancel deactivated   │
  │     deliveryStart, status }  │      │        (group, quarter) pairs    │
  └──────────────────────────────┘      │                                  │
                                        │ .getLastSignalsForQuarters(set)  │
                 feeds ◄────────────────│   └─ used by time-advance step   │
          SourcingCostController        │                                  │
          GET /api/sourcing-cost        │ steering_signals.jsonl           │
          (VWAP of all BUY orders)      │   { group, deliveryStart/End,    │
                                        │     commandedPowerMw, energyMwh }│
                                        └──────────────────────────────────┘
                                                         │
                                                         ▼
                                                  EV Charging Groups
                                                  (A / B / C / D / E / F)

- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
```

---

## Optimizer — Two-Phase State Machine

```
                    OrderBookUpdatedEvent
                            │
                    ┌───────▼───────┐
                    │ Time Advance  │  expireBeforeTime(now)
                    │               │  consumeChargedEnergy()
                    └───────┬───────┘
                            │
                    ┌───────▼───────┐
                    │ Stale Guard   │  quarter < now  ──►  DROP
                    └───────┬───────┘
                            │
             ┌──────────────▼──────────────┐
             │ totalPos < totalNeed?       │
             └────┬───────────────────┬────┘
                 YES                 NO
                  │                   │
         ┌────────▼────────┐ ┌────────▼────────┐
         │ FILL-UP         │ │ ARBITRAGE       │
         │                 │ │                 │
         │ scan ALL        │ │ anchor on       │
         │ asks (asc)      │ │ updated qtr     │
         │                 │ │                 │
         │ per-qtr         │ │ ask dropped?    │
         │ hard limit      │ │  BUY here       │
         │ soft limit      │ │  SELL others    │
         │                 │ │                 │
         │ proportional    │ │ bid rose?       │
         │ allocation      │ │  SELL here      │
         │ per group       │ │  BUY others     │
         └────────┬────────┘ └────────┬────────┘
                  └─────────┬─────────┘
                            │
                   ┌────────▼────────┐
                   │ dispatch()      │
                   │ SteeringSignals │
                   └─────────────────┘
```

---

## Fill-up Buy Constraints (per quarter)

| Constraint | Formula | Purpose |
|---|---|---|
| Hard Limit | `maxBuyable(q) − position(q)` | Physical power ceiling |
| Soft Limit | `Σ min(remaining_g, maxPower_g × 0.25h)` | Only buy what groups still need |
| Order Cap | `min(shortfall, hard, soft, askEntry.qty)` | Final buy quantity |

Group allocation after each buy mirrors the steering signal proportional logic, keeping procurement and dispatch self-consistent.

---

## Steering Signal Allocation (per quarter)

```
For each quarter Q (time-sorted):
  active_groups = groups where window covers Q AND remaining > 0

  desire_g   = min(remaining_g, maxPower_g × 0.25h)
  totalDesired = Σ desire_g

  if position[Q] ≥ totalDesired:
      allocation_g = desire_g          ← every group gets what it wants
  else:
      allocation_g = desire_g × (position[Q] / totalDesired)   ← scale down fairly
```

Cancelled slots (group no longer receiving energy) get an explicit zero-power signal.

---

## Time-Awareness — Quarter Lifecycle

```
Quarter Q delivery window passes (Q.startTime < now)
    │
    ├─► PositionManager.expireBeforeTime(now)
    │       removes Q from position map
    │       returns { Q → MWh } expired set
    │
    ├─► SteeringSignalDispatcher.getLastSignalsForQuarters({Q, ...})
    │       returns last commanded energy per (group, Q) pair
    │       (proxy for actual energy consumed — groups comply with last signal)
    │
    └─► ChargingNeedAggregator.consumeChargedEnergy(map)
            deducts from groupRemaining, floored at 0
            totalRemainingNeed() now reflects actual outstanding need
```

Stale order-book events (from market delay or manipulation) are **filtered out** at the optimizer entry point — the order book itself is not cleaned, but past-quarter data never influences procurement or dispatch decisions.

---

## REST API Surface

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/order` | Submit a market order (buy or sell) |
| `GET` | `/api/orderbook/overview` | All quarters: best bid/ask price + qty |
| `GET` | `/api/orderbook?deliveryStartTime=` | Full depth snapshot for one quarter |
| `GET` | `/api/sourcing-cost` | VWAP, total volume, total cost from buy log |

---

## Audit Logs

| File | Content |
|---|---|
| `market_orders.jsonl` | Every executed buy/sell: orderId, side, qty, price, quarter, status |
| `steering_signals.jsonl` | Every EV command: group, deliveryStart/End, commandedPower, commandedEnergy |

---

## Configuration (`application.properties`)

```
smart-asset.trading-date=2025-01-01

smart-asset.charging-groups[0].name=GroupA
smart-asset.charging-groups[0].start-time=08:00
smart-asset.charging-groups[0].end-time=12:00
smart-asset.charging-groups[0].needed-charge-mwh=1.2
smart-asset.charging-groups[0].max-power-mw=0.5
# ... groups B–F follow the same pattern
```

---

## Tech Stack

- **Spring Boot 3.5 / Kotlin 1.9 / Java 21**
- Event-driven: `@EventListener` + `@Synchronized` (single-threaded optimizer loop)
- Config binding: `@ConfigurationProperties`
- No external message broker — in-process Spring events
