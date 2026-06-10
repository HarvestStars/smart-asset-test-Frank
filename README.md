# Smart Asset — EV Fleet Energy Optimizer

Automated energy procurement and EV charging dispatch for fleet operators.
Ingests live market order-book data, decides what to buy/sell and when, and
issues per-quarter charging commands to EV groups — all in a single
event-driven loop.

---

## Pipeline Architecture

Every market event flows through the same 6-layer pipeline.
The only branching point is inside the Optimizer (Fill-up vs Arbitrage).
Procurement and dispatch share one stateless allocation strategy, so both layers
interpret the position layout in exactly the same way.

```
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
  SMART ASSET PIPELINE
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  ① Market Input            ② Order Book                ③ Optimizer

  ┌─────────────────┐       ┌────────────────────────┐    ┌────────────────────────────┐
  │ REST API        │──────►│ OrderBookService       │───►│ OptimizationService        │
  │                 │       │                        │    │                            │
  │ POST /order     │       │ order books by quarter │    │ expire delivered positions │
  │ BUY / SELL      │       │ best bid / ask levels  │    │ reject stale events        │
  │ price / quantity│       │ globally sorted asks   │    │                            │
  │ delivery window │       │                        │    │ totalPos < totalNeed       │
  └─────────────────┘       │ publishes              │    │   → FILL-UP                │
                            │ OrderBookUpdatedEvent  │───►│ totalPos ≥ totalNeed       │
                            └────────────────────────┘    │   → ARBITRAGE              │
                                                          └─────────────┬──────────────┘
                                                                        │ reads / updates
                                                                        ▼
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  ④ Position & Need Management

  ┌────────────────────────────┐      ┌────────────────────────────────┐
  │ PositionManager            │      │ ChargingNeedAggregator         │
  │                            │      │                                │
  │ positions:                 │      │ groups: charging windows,      │
  │ Map<quarter, MWh>          │      │ power limits and actual need   │
  │                            │      │                                │
  │ market inventory layout    │      │ groupRemaining:                │
  │ (not permanently assigned  │      │ Map<group, uncharged MWh>      │
  │  to charging groups)       │      │                                │
  │                            │      │ decreases only after delivery  │
  │ adjust / expire / snapshot │      │ maxBuyable / window checks     │
  └──────────────┬─────────────┘      └───────────────┬────────────────┘
                 │ positions                            │ actual remaining need
                 └──────────────────┬───────────────────┘
                                    ▼
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  ⑤ Charging Allocation Policy

                ┌──────────────────────────────────────────────┐
                │ ChargingAllocationStrategy                   │
                │                                              │
                │ default: PowerWeightedAllocationStrategy     │
                │                                              │
                │ allocatePositions(groups, need, positions)   │
                │   → allocations[(group, quarter)]            │
                │   → uncovered remaining by group             │
                │                                              │
                │ allocateQuarter(groups, remaining, q, MWh)   │
                │   → useful capacity in one quarter           │
                │   → next remaining snapshot                  │
                │                                              │
                │ stateless: no permanent group assignment     │
                └───────────────┬──────────────────┬───────────┘
                                │                  │
                    procurement │                  │ dispatch
                                ▼                  ▼
- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

  ⑥ Execution Clients

  ┌──────────────────────────────┐      ┌──────────────────────────────────┐
  │ MarketOrderClient            │      │ SteeringSignalDispatcher         │
  │                              │      │                                  │
  │ execute optimizer BUY / SELL │      │ dispatch(current positions)      │
  │ call OrderBookService        │      │                                  │
  │ append market_orders.jsonl   │      │ allocation plan                  │
  │                              │      │   → SteeringSignal per           │
  │ GET sourcing cost reads log  │      │     (group, quarter)             │
  └──────────────────────────────┘      │   → emit changed signals only    │
                                        │   → zero cancelled assignments   │
                                        │                                  │
                                        │ steering_signals.jsonl           │
                                        └────────────────┬─────────────────┘
                                                         │
                                                         ▼
                                                  EV Charging Groups
                                                  (A / B / C / D / E / F)

- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
```

---

## Optimizer — Two-Phase State Machine

```
                         OrderBookUpdatedEvent
                                  │
                         ┌────────▼────────┐
                         │ Time Advance    │  expire positions
                         │                 │  consume delivered energy
                         └────────┬────────┘
                                  │
                         ┌────────▼────────┐
                         │ Stale Guard     │  past quarter ──► DROP
                         └────────┬────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │ totalPos < totalNeed?     │
                    └──────┬─────────────┬──────┘
                          YES           NO
                           │             │
             ┌─────────────▼──────┐ ┌────▼─────────────────────┐
             │ FILL-UP            │ │ ARBITRAGE                │
             │                    │ │                          │
             │ allocate existing  │ │ detect improved ask/bid  │
             │ positions against  │ │ on updated quarter       │
             │ actual group need  │ │                          │
             │                    │ │ for each candidate move  │
             │ result:            │ │  1. hypothetically sell  │
             │ uncovered demand   │ │     source position      │
             │                    │ │  2. rebuild uncovered    │
             │ scan asks by price │ │     group demand         │
             │                    │ │  3. allocate destination │
             │ candidate = min(   │ │     against uncovered    │
             │  total shortfall,  │ │  4. cap transferable qty │
             │  hard headroom,    │ │                          │
             │  ask quantity)     │ │ totalPos stays constant  │
             │                    │ │                          │
             │ allocate candidate │ │ no permanent group       │
             │ once → buy useful  │ │ assignment is stored     │
             │ qty + next         │ │                          │
             │ uncovered demand   │ │                          │
             └──────────┬─────────┘ └───────────┬──────────────┘
                        └────────────┬───────────┘
                                     │
                    ┌────────────────▼────────────────┐
                    │ Rebuild allocation plan         │
                    │ from final positions + actual   │
                    │ group remaining need            │
                    └────────────────┬────────────────┘
                                     │
                          ┌──────────▼──────────┐
                          │ dispatch changed    │
                          │ SteeringSignals     │
                          └─────────────────────┘
```

---

## Fill-up Buy Constraints (per quarter)

| Constraint | Formula | Purpose |
|---|---|---|
| Hard Limit | `maxBuyable(q) − position(q)` | Physical power ceiling |
| Uncovered Remaining | `allocatePositions(groups, actualRemaining, positions).remainingByGroup` | Demand not covered by the current layout |
| Candidate Quantity | `min(shortfall, hardHeadroom, askEntry.qty)` | Maximum allowed by demand, physical capacity and market supply |
| Final Buy Quantity | `allocateQuarter(groups, uncoveredRemaining, q, candidateQuantity).allocatedEnergy` | Buy only the candidate energy assignable in this quarter |

Fill-up calls `allocateQuarter` once per ask. That single result provides both
the useful buy quantity (`allocatedEnergy`) and the `remainingByGroup` snapshot
for the next ask. This prevents two different quarters in the same scan from
covering the same group demand.

`uncovered remaining` is temporary. It is rebuilt from actual group demand and the
latest positions on every optimization cycle, so arbitrage can freely relocate
inventory without creating a permanent group assignment.

---

## Shared Allocation Strategy

`PowerWeightedAllocationStrategy` is the current implementation of
`ChargingAllocationStrategy`. Both procurement and dispatch call this module.

```
allocatePositions(groups, remainingNeed, positions):
  remaining = copy of actual remaining need

  for each quarter Q in delivery-time order:
      result = allocateQuarter(groups, remaining, Q, position[Q])
      store result.allocations as (group, Q) assignments
      remaining = result.remainingByGroup

  return:
      allocations       → used by SteeringSignalDispatcher
      remainingByGroup  → uncovered remaining used by Optimizer

allocateQuarter(groups, remaining, Q, availableEnergy):
  active_groups = groups whose window covers Q and remaining > 0

  desire_g = min(remaining_g, maxPower_g × 0.25h)

  if availableEnergy ≥ Σ desire_g:
      allocation_g = desire_g
  else:
      allocation_g = desire_g × (availableEnergy / Σ desire_g)
                     ↑ proportional to each group's quarter power demand

  return allocations and the next remaining snapshot
```

The strategy is stateless and interchangeable. A future membership-priority
implementation can replace the power-weighted policy, and the same policy will
automatically affect both Fill-up procurement and steering-signal dispatch.

Cancelled `(group, quarter)` slots still receive an explicit zero-power signal.

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
