# Day-trader mode for the robot fund

## Context

The current pipeline buys at analyst rating ≥7 and only sells when the analyst re-rates a held ticker ≤3. Once a stock is bought, the analyst rarely produces a low enough re-rating to trigger a sale, so positions accumulate and cash drains. With position cap = 10% of equity, 10 buys saturate the book, and the daily cap of 5 buys means it usually happens within ~2 days.

The user wants the fund to behave more like a day trader: many round-trips per day, taking small profits and cutting small losses, so capital recycles. The conference deadline (2026-05-25) means we still need *net positive* outcomes, not just activity — but the agent-architecture prize also rewards visible, defensible decision-making.

Root causes of the cash-stuck behaviour (verified in code):

1. **No mechanical exits.** `risk.clj` only sells when analyst re-rates ≤3. There is no take-profit or stop-loss rule. (`src/com/robotfund/agents/risk.clj:141-152`)
2. **Analyst is position-blind.** The prompt at `src/com/robotfund/agents/analyst.clj:20-45` tells the LLM nothing about whether the ticker is currently held, the entry price, or unrealised P&L. It can never produce a sell rationale of the form "we're up 3%, take it".
3. **Positions are large relative to the book.** 10% per position × 10 positions = fully invested.
4. **Day cap discourages activity.** Default `:settings/max-trades-per-day = 5` (`risk.clj:23-25`).
5. **Order type is plain market-day, no bracket / OCO.** (`alpaca.clj:49-58`)

The infrastructure to fix this is already mostly in place:

- Scanner already includes held positions as candidates each cycle (`scanner.clj:72-74`, commit aec641c) — so every cycle, every holding *could* be re-evaluated, we just need richer logic.
- Settings widget pattern exists (`dashboard.clj:193-214`, route `/settings/max-trades`, schema `:settings`) — extend it for new knobs without code changes.
- Alpaca position object already exposes `:avg_entry_price`, `:unrealized_pl`, `:unrealized_plpc`, `:qty_available` (used in `dashboard.clj:101-122`, `executor.clj:142-158`).
- Risk Manager is pure Clojure (ADR 0006) — adding rules there is in-architecture.

## Strategy

Layered. Each layer is shippable and testable on its own; layers compose.

### Layer 1 — Mechanical exit rules in Risk Manager (highest impact, smallest change)

For every currently-held position, generate a sell proposal **independent of the analyst** when:

- `unrealized_plpc ≥ take_profit_pct` (default **+1.5%**), OR
- `unrealized_plpc ≤ -stop_loss_pct` (default **-2.0%**)

These exits do NOT consume the daily-buy cap (sells are not counted today either — confirm in `risk.clj:43-50`). They run as a *first pass* in the risk cycle, before the analyst-driven proposals are evaluated, so capital is freed up before the next buy decisions are made.

**Files to change:**
- `src/com/robotfund/agents/risk.clj` — new `exit-check` function called from `run-risk` before `process-analysis` loop. It iterates `(get-positions)` and emits `:trade-proposal` entries with `:trade-proposal/decision :approved`, `:trade-proposal/action :sell`, `:trade-proposal/reason "take-profit at +1.83%"` etc. These need a synthetic `:trade-proposal/analysis-id` — either a special sentinel or extend the schema to make analysis-id optional for exit proposals.
- `src/com/robotfund/schema.clj` — make `:trade-proposal/analysis-id` optional (or add `:trade-proposal/source [:enum :analyst :exit-rule]` for clarity), add settings keys.
- Settings: add `:settings/take-profit-pct`, `:settings/stop-loss-pct` to the schema and default-settings map. Default both `nil`/disabled until user opts in.

**Tradeoffs:**
- Pure rules-based, no LLM in the exit path → fast, deterministic, auditable. Fits ADR 0006.
- Doesn't catch news-driven exits (e.g. a sudden negative headline) — that stays the analyst's job.
- The 15-min cycle means an exit could fire up to 15 min late. Acceptable for paper-trading with daily horizons; not real-money HFT.

### Layer 2 — Smaller positions + relax daily cap (configurable, no code logic changes)

Add to the settings widget:
- `max-position-pct` (default 0.05 = 5%) — currently hardcoded `risk.clj:18`
- `min-buy-rating` (default 6, was 7) — currently hardcoded `risk.clj:20`
- Disable `max-trades-enabled` by default in day-trader mode (the position/sector caps + take-profit/stop-loss already provide guardrails)

**Files:**
- `src/com/robotfund/agents/risk.clj` — read these from settings instead of constants
- `src/com/robotfund/dashboard.clj` — extend `settings-widget` and `save-max-trades` (rename to `save-settings`)
- `src/com/robotfund/schema.clj` — extend `:settings` map

5% positions × 30% sector cap means up to 6 positions per sector, 20+ across the book. Combined with Layer 1 exits, capital churns rather than locking up.

### Layer 3 — Position-aware analyst prompt

Pass the analyst more context for held tickers so its rating is exit-aware:

```
You currently hold 47 shares of NVDA at avg cost $412.50.
Current price $419.80. Unrealized P&L: +$343 (+1.77%).
Position age: ~1.4 trading days.
```

The LLM can then produce a confident "sell" rating on a stock that's still fundamentally fine but has hit a sensible trim level. Risk Manager still applies the rating-≤3 rule on the analyst path; mechanical exits in Layer 1 catch what the analyst misses.

**Files:**
- `src/com/robotfund/agents/analyst.clj:20-45` — extend `analyst-prompt` to accept and inject position context
- `src/com/robotfund/agents/analyst.clj` `run-analyst` — pass `(alpaca/get-positions)` into the prompt-build
- Could share a `position-for` helper with `risk.clj`

### Layer 4 — (Optional) Tighter cycle

Move from 15-min to **5-min** cycles in `src/com/robotfund/schedule.clj:41-44`. Same staggered offsets scaled down (Scanner :00, News :01, Analyst :02, Risk :03, Executor :04). This makes Layer 1 exits 3× more responsive and gives more genuine intraday opportunities.

**Caveats:** ~3× more LLM calls (Anthropic spend), ~3× more Alpaca calls (paper trading rate-limit is 200 req/min, comfortable). Defer until Layers 1-3 are working.

### Layer 5 — (Optional) Bracket orders

Replace the plain market-day order with an Alpaca **OTOCO bracket** (entry + take-profit limit + stop-loss): `place-order` accepts `order_class: "bracket"`, `take_profit: {limit_price: ...}`, `stop_loss: {stop_price: ...}`. Alpaca then handles exits server-side.

**Pros:** Exits fire instantly on price touch, not at next 15-min cycle. Cleaner.
**Cons:** Significant change to `alpaca.clj`, `executor.clj`, `schema.clj` (orders now have legs). Less visible in our XTDB-driven UI; harder to demo agent reasoning. **Recommend skipping for the conference** and using Layer 1 instead — the agent-architecture prize values traceable agent decisions over server-side automation.

## Recommended order of work

1. **Layer 1** (core fix) — implement, test on weekend with REPL using `(risk/run-risk ctx)`, observe a synthesized exit fire when a held position has the right P&L. Ship.
2. **Layer 2** (knobs) — wire settings, expose in dashboard. Ship.
3. **Dry-run a day** with default `take_profit=1.5%`, `stop_loss=2.0%`, `max_position=5%`, `min_buy_rating=6`, daily-cap disabled. Observe `bb proposals` and the timeline. Tune defaults.
4. **Layer 3** (analyst awareness) — once mechanical exits are stable.
5. **Layer 4** (5-min cycle) — only if Anthropic spend headroom allows.
6. **Layer 5** — likely don't ship.

## Verification

- **Layer 1 unit-ish test (REPL):** stub a position with known `unrealized_plpc`, call `exit-check`, assert it produces a sell proposal with the right reason. Run via nREPL on :7888.
- **Layer 1 live test:** during market hours, force a candidate that's currently up >1.5% (or temporarily lower the threshold to 0.1% in REPL), run `bb risk` then `bb execute`, observe the sell on the Alpaca dashboard and in `/timeline`.
- **Layer 2:** flip the dashboard settings, confirm new constants take effect on next `bb risk` without restart (XTDB read each cycle per `load-settings` already).
- **End-to-end sanity (after Layers 1-2):** liquidate Alpaca paper account via the Alpaca web UI for a clean baseline (or add a `bb liquidate` task — small follow-up). Run `bb pipeline` repeatedly through a market session, confirm cash recycles.
- **Lint:** `clj-kondo --lint src` after each edit.

## Critical files

- `src/com/robotfund/agents/risk.clj` — Layers 1, 2 (main change site)
- `src/com/robotfund/agents/analyst.clj` — Layer 3
- `src/com/robotfund/dashboard.clj` — Layer 2 (settings widget extension)
- `src/com/robotfund/schema.clj` — Layers 1, 2 (schema additions)
- `src/com/robotfund/schedule.clj` — Layer 4
- `docs/trading.md` — update to reflect new behaviour
- `docs/adr/0007-day-trader-exit-rules.md` — new ADR documenting take-profit / stop-loss + smaller positions, since it materially changes ADR 0006's rules

## Existing utilities to reuse

- `risk/load-settings` (`risk.clj`) — extend, don't duplicate
- `risk/position-for` (`risk.clj:52-53`) — reuse for analyst context
- Alpaca position fields `:avg_entry_price`, `:unrealized_plpc`, `:qty_available` — already returned by `alpaca/get-positions`
- Settings widget pattern in `dashboard.clj:193-228` — extend the same form
- `schema/validate!` — keep using for all new entities

---

## Conversation state (paused 2026-04-27)

This plan was drafted in a planning session that was paused before three design choices were finalised. When resuming, answer Q1–Q3 below before implementation starts.

### Open questions awaiting answers

**Q1. Default TP/SL thresholds.** What percentages should the take-profit and stop-loss exits default to? They are dashboard-adjustable, so this is just the seed value.

| Option | TP | SL | Notes |
|--------|----|----|-------|
| Aggressive | +1.0% | -1.5% | Many small wins, tight stops. Most demo activity. Whipsaw risk on volatile names. |
| Balanced (recommended) | +1.5% | -2.0% | Defensible defaults, decent round-trip frequency on liquid mid/large caps. |
| Conservative | +3.0% | -3.0% | Closer to swing trading. Cash recycles slowly. |

**Q2. Exit mechanism.** Where should TP/SL fire?

- **Risk-Manager rule pass (recommended)** — pure Clojure check at top of risk cycle, emits sell proposals. Fits ADR 0006, fully visible in `/timeline` and XTDB, ~15-min latency.
- **Bracket orders on Alpaca** — each buy sent as OTOCO with TP limit + SL stop legs. Server-side instant exits. Bigger refactor across `alpaca.clj`, `executor.clj`, `schema.clj`; exits less visible in agent UI.
- **Both** — bracket on entry + Risk-Manager fallback. Belt-and-braces but ~2× the surface area to test before May 18.

**Q3. Optional layers in scope** (multi-select):

- Layer 3: position-aware analyst prompt (pass entry price, unrealized P&L, age to the LLM)
- Layer 4: 5-min cycle (~3× LLM and Alpaca call volume)
- `bb liquidate` task for clean restarts / dry-run resets
- New ADR 0007 documenting day-trader exit rules + position-cap change (supersedes parts of ADR 0006)

### How to resume

In a fresh session, paste:

> Resume the day-trader plan at `docs/day-trader-plan.md`. Read it, then ask me Q1–Q3 from the "Open questions awaiting answers" section. Once I answer, finalise the plan and start implementing Layer 1.

Or just answer Q1/Q2/Q3 directly and I'll fold the answers in and start.
