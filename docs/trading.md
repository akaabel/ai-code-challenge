# How the Fund Trades

A plain-language guide to what the fund does, why it does it, and what to expect. Intended for someone who wants to use or operate this solution without reading the source code.

---

## The fund in one paragraph

The fund starts with $100,000 in Alpaca paper trading (a simulated NYSE/NASDAQ environment). Every 15 minutes during market hours, a pipeline of five autonomous agents scans the market, reads news, rates candidates with an LLM, checks hard risk rules, and places market orders. The goal is to grow the portfolio by making data-driven buy/sell decisions through the trading week.

---

## The trading cycle

The pipeline runs automatically every 15 minutes, Monday–Friday, between 09:30 and 16:00 US Eastern time. Outside those hours it is silent — no orders are placed and no scans happen.

Within each 15-minute window, five agents run in sequence with staggered start times:

| Offset | Agent | What it does |
|--------|-------|-------------|
| +0 min | Scanner | Finds tickers worth looking at |
| +2 min | News | Fetches headlines and scores sentiment |
| +5 min | Analyst | Rates each candidate 1–10 |
| +7 min | Risk Manager | Approves, resizes, or rejects proposals |
| +10 min | Executor | Places orders and reconciles fills |

Each agent writes its output to the database (XTDB). The next agent in the chain picks up from there. If an agent fails for one ticker, it logs the error and moves on — the rest of the cycle is unaffected.

---

## Stage 1 — Scanner: what to look at

The scanner does not have a fixed watchlist. Every cycle it asks Alpaca's screener for:
- The top 20 **gainers and losers** of the day (biggest price movers)
- The top 20 **most actively traded** stocks by volume

It then filters out stocks below $5 (to avoid penny stocks and warrants). From the resulting list (up to ~50 tickers), it checks each one for two triggers:

- **Price trigger**: the stock has moved more than ±1.5% since the prior close.
- **Volume trigger**: today's volume is more than 2× the 20-day average.

Any ticker that fires at least one trigger becomes a **candidate** and is stored for the next stage. If the screener is unavailable (e.g. outside market hours), a fallback list of 10 mega-cap stocks is used instead.

---

## Stage 2 — News agent: sentiment scoring

For each new candidate, the news agent fetches recent headlines from Alpaca's news API and sends them to an LLM (currently Gemini). The LLM reads the headlines, writes a short summary, and assigns a **sentiment score between −1.0 and +1.0**:

- **+1.0** = strongly positive news (earnings beat, major win, upgrade)
- **0.0** = neutral or mixed
- **−1.0** = strongly negative news (miss, lawsuit, downgrade)

The raw LLM prompt and response are stored in the database for auditability. The sentiment score travels with the candidate to the next stage.

---

## Stage 3 — Analyst: rating 1–10

The analyst takes the candidate plus its news report and asks the LLM for a deeper judgment. The LLM is given the price movement, volume data, sentiment score, and headlines, and returns:

- A **rating from 1 to 10** (10 = strongest conviction)
- A recommended **action**: buy, sell, or hold
- A **reasoning paragraph** explaining the decision

The analyst does not place trades. It only produces a scored recommendation. Everything is stored; nothing is acted on until the Risk Manager reviews it.

---

## Stage 4 — Risk Manager: hard rules

The Risk Manager is pure logic — no LLM. It applies a fixed set of rules and either approves, resizes, or rejects the analyst's recommendation. These rules are the primary safety layer.

**Buy conditions (all must pass):**

| Rule | Threshold | Reason |
|------|-----------|--------|
| Minimum rating to buy | 7 / 10 | Only act on high-conviction signals |
| Maximum position size | 10% of equity | No single stock dominates the portfolio |
| Maximum sector exposure | 30% of equity | Sector diversification |
| Maximum buys per day | configurable (default 5, can be disabled) | Limit churn; see note below |

**Sell conditions (all must pass):**

| Rule | Threshold |
|------|-----------|
| Maximum rating to sell | 3 / 10 |
| Must hold shares | position > 0 |

**Other rules:**
- **Hold** recommendations are always rejected (no trade required).
- **Short selling** is never permitted.
- If a buy would push a position above 10%, the order is **resized** to the maximum allowable quantity rather than rejected outright.
- If a buy would push sector exposure above 30%, the order is resized (or rejected if there is no room at all).

### The 5-buys-per-day limit

This is a global cap across all tickers, not per ticker. Sells are not counted against it. The limit was chosen to keep the system conservative and to partially compensate for a known gap described below (see *Known limitations*).

The cap is configurable from the **portfolio page settings widget** — no code change needed. You can raise or lower the number, or toggle the limit off entirely to rely solely on the position and sector caps.

With 26 cycles per trading day, the fund will typically hit this limit early (within the first two hours) and make no further buys for the rest of the day. If you want more activity, raise the number or disable the cap from the settings widget.

---

## Stage 5 — Executor: placing orders

The executor runs in two phases:

1. **Reconcile pending orders** — checks every open order from previous cycles, asks Alpaca for its current status, and writes the result to the database. Orders older than 30 minutes that are still pending are cancelled on Alpaca and marked cancelled locally.

2. **Place new orders** — for every approved or resized proposal that does not yet have an order, the executor places a **market order** on Alpaca with `time_in_force: "day"`.

A **market order** buys or sells at whatever the current best price is. This guarantees execution but not price. It is the fastest and simplest order type, appropriate for liquid large-cap stocks.

`time_in_force: "day"` means the order is valid only for the current trading session. It expires automatically at 16:00 ET if not filled.

After placing each order, the executor waits 3 seconds and checks whether a fill has already arrived. If filled, it records the fill (quantity and price) immediately. If still pending, it moves on and will reconcile on the next cycle.

---

## What happens when the pipeline runs outside market hours

If you manually run the pipeline (e.g. `bb pipeline` or `bb execute`) while the market is closed, the following happens:

**Scanner, news, analyst, risk** all run normally and write their results to the database. These are read-only operations against the market data API; closing hours do not affect them.

**Executor**: Alpaca paper trading accepts market orders submitted outside market hours and queues them for the next open. This means:

- An `:order` entity is written to the database with status `:pending`.
- At market open tomorrow, Alpaca will attempt to fill the order at the opening price.
- When the first executor cycle of tomorrow runs (~09:40 ET), it will see the order as stale (>30 minutes old) and attempt to cancel it — but by that point the order may already have filled.
- If already filled, the cancel is ignored by Alpaca and the executor correctly records the fill.

This behaviour is mostly harmless but produces a subtlety: **you may end up with a filled position you did not intend to take**, based on yesterday's analysis. If you want to prevent this, do not run the executor manually while the market is closed.

The scheduled pipeline avoids this because it only runs during market hours.

---

## Known limitations and gaps

### Position/sector snapshot gap

The Risk Manager snapshots your current positions **once** at the start of each risk cycle. It then evaluates all pending analyses against the same snapshot. If two candidates in the same sector are both approved in a single cycle, each sees the pre-cycle sector exposure — neither knows the other was just approved. Together they could push sector exposure past 30%, or a single ticker past 10%.

This gap is bounded in practice by the 5-buy daily cap (keeping each batch small) and by the fact that approved orders take time to fill, so the next cycle's snapshot will reflect reality. But it is theoretically possible to slightly overshoot the caps within a single cycle.

A clean fix would be to accumulate approved quantities in-memory during the evaluation loop (the same way the daily trade counter is already updated in-memory) and deduct them from the exposure calculations in real time.

### Tomorrow's pipeline after a market-closed run

If the pipeline ran today while the market was closed and created proposals:

- Tomorrow's scanner will discover a fresh set of candidates based on tomorrow's movers.
- Those new candidates go through the full pipeline and may produce new proposals for some of the same tickers.
- The risk manager will call `get-positions` from Alpaca. If today's orders filled at open before the risk cycle runs (~09:37 ET), the positions will reflect reality and the caps will apply correctly.
- If today's orders have not yet been reconciled (filled or cancelled) by the time the risk cycle runs, the risk manager may see a stale position (e.g. 0 shares of NVDA) and approve a second buy for the same ticker.

The race window is roughly 7 minutes (market open at 09:30 to the risk cycle at ~09:37). In practice, Alpaca paper fills are typically reflected within seconds, so this race is unlikely to matter. But it is not guaranteed.

### LLM judgment quality

The analyst rating is only as good as the LLM's interpretation of recent headlines. The model has no access to fundamentals, earnings calendars, or macroeconomic data. It can be wrong. All LLM calls are logged in full (prompt + response) so you can review every decision after the fact.

### No intraday position updates

The portfolio page shows the current Alpaca position values at page load. It does not recalculate exposure in real time between cycles. To see the latest state at any moment, refresh the portfolio page or look at the Alpaca dashboard directly.

---

## Monitoring the fund

- **`/`** — portfolio page: equity, cash, today's P&L, open positions with unrealised gains/losses.
- **`/timeline`** — live agent activity: every scan, news report, analysis, proposal, order, and fill, newest first. Refreshes every 3 seconds.
- **`/trade/:id`** — drilldown for a specific trade: the full chain from candidate through fill, including the raw LLM prompts and responses.
- **`bb sector-exposure`** — live sector breakdown from the command line.

Logs are written to stdout. If running in the container, use `podman logs -f robotfund`.
