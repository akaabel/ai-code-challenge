# ADR 0006: Risk Manager as Pure Clojure — Hard Rules, No LLM

**Status:** Accepted · **Date:** 2026-04-23

## Context

ADR 0003 establishes the LLM/code split: LLM handles qualitative judgment, Clojure handles hard constraints. The Risk Manager is the clearest test of this principle. It sits between the Analyst (which produces a rating and a recommended action) and the Executor (which places real paper orders). A mistake here means real money moving in the wrong direction.

The question: should the Risk Manager use an LLM to weigh risk holistically, or should it be pure deterministic Clojure rules?

## Decision

The Risk Manager is **pure Clojure — no LLM call, no prompt engineering, no JSON parsing**. It applies a fixed set of hard rules to each analyst recommendation and emits a `:trade-proposal` with decision `:approved`, `:resized`, or `:rejected`.

### Rules (implementation: `src/com/robotfund/agents/risk.clj`)

| Rule | Value | Rationale |
|---|---|---|
| Min rating to buy | ≥ 7 / 10 | Only act on high-conviction analyst signals |
| Max rating to sell | ≤ 3 / 10 | Only exit positions with clear negative signal |
| Max position size | 10% of equity | No single stock dominates the fund |
| Max sector exposure | 30% of equity | Prevent sector concentration risk |
| Max trades per day | 5 | Limit churn and API cost |
| Never short | enforced | No short positions — paper fund scope |

### Position sizing

Buy quantity is computed as:

```
remaining_budget  = (equity × 10%) − current_position_value
sector_headroom   = (equity × 30%) − current_sector_exposure
shares            = floor(min(remaining_budget, sector_headroom) / current_price)
```

If `shares` is less than what the raw position cap would allow, the proposal is `:resized` rather than `:rejected`.

### Sector map and the `:other` bucket

Sector classification uses a hardcoded `sector-map` of known tickers. Tickers not in the map are assigned to `:other`, which shares a single 30% ceiling across all unknown tickers.

This is a deliberate tradeoff: the **position cap is the primary guard** (10% per ticker, always enforced regardless of sector). The sector cap is a secondary guard against concentration. Grouping unknowns into `:other` is conservative — it limits how much total exposure the fund can take in unclassified names — without requiring a third-party sector data API. The `bb sector-exposure` task monitors `:other` headroom in real time.

## Alternatives Considered

| Option | Why not |
|---|---|
| **LLM-based risk judgment** ("is this trade risky?") | LLMs are unreliable at portfolio math, cannot see real-time position data, and occasionally hallucinate numbers. A malformed response could approve an oversized trade. Unacceptable for a money-moving decision. |
| **LLM as risk advisor with Clojure override** (LLM recommends, code vetoes) | Adds an LLM call with no upside — the veto is the actual enforcement, making the LLM advice decorative. Costs tokens and latency for nothing. |
| **Dynamic sector classification via LLM** (ask LLM to classify new tickers) | Eliminates the `:other` bucket, but adds an LLM call per novel ticker, introduces a hallucination vector into a hard-rule system, and complicates the audit trail. Not worth it while the position cap already bounds single-stock exposure. |
| **Third-party sector API** (Financial Modeling Prep, etc.) | Adds an external dependency and another API key. Worthwhile if `:other` accumulation becomes a demonstrated problem during the dry run; revisit in ADR 0007 if needed. |

## Rationale

- **The LLM physically cannot violate hard rules.** Any malformed analyst output is rejected at schema validation before it reaches the Risk Manager. The Risk Manager then applies rules in pure Clojure — no LLM response can override a position cap.
- **Deterministic rules are auditable.** Every proposal includes a `:trade-proposal/reason` string explaining exactly which rule fired. Judges can read the XTDB trail and reconstruct the decision in full.
- **The 10%/30%/5 numbers are legible to a non-technical audience.** During the 15-minute conference presentation, "the risk manager caps any single stock at 10% and any sector at 30%" is a sentence. An LLM-based risk score is not.
- **Simplicity is a feature for a week-long unattended run.** No prompt drift, no token budget surprises, no LLM downtime risk on the risk-enforcement path.

## Tradeoffs Accepted

- **`:other` bucket coarseness.** All tickers not in the sector map share one 30% ceiling. This can over-restrict genuinely diverse positions or under-restrict concentrated exposure in a single unnamed sector. Mitigated by the 10% per-position cap; monitored via `bb sector-exposure`.
- **Static thresholds.** The 7/10 buy rating floor and 5 trades/day cap are fixed. They cannot adapt to market regimes (e.g., high-volatility days might warrant fewer trades). Acceptable for a one-week challenge window.
- **No intraday position update.** The Risk Manager snapshots positions at the start of each cycle. Multiple proposals approved in one cycle could collectively push past a limit before any fills are reconciled. Mitigated by the 5-trade/day cap keeping batch sizes small.
