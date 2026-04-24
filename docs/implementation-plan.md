# Implementation Plan

Incremental delivery. Each step is small, ends with a runnable/testable state, and is committed separately so you can review the diff and try it out before we move on.

This document is **living** — we update it as we learn, reorder steps if something surfaces, and check off status as we go.

## How we work through this

1. Before starting a step, glance at the **What you'll be able to test** box.
2. I implement the step; the commit message matches `Step N: <short title>`. Do not do the commit, I will do that myself.
3. You start the app (`./ops/dev.sh`) if it isn't already running.
4. I connect to the nREPL on `localhost:7888` via `bb` and run the tests myself.
5. On green I mark the step done and suggest a commit message. On failure I fix and re-test.
6. You commit and we move to the next step.

## Status legend

- `[ ]` not started
- `[~]` in progress
- `[x]` done

## Calendar anchors

- **Today:** 2026-04-20
- **Dry run target:** 2026-05-11 (fund running unattended)
- **Trading starts:** 2026-05-18
- **Deadline:** 2026-05-25 15:00

---

## Step 1 — Biff skeleton boots `[x]`

**Goal:** Generate a Biff project and get the default welcome page responding on `localhost:8080`.

**What I'll do:**
- Generate the Biff app at the repo root (project name `robotfund`)
- Strip scaffold features we won't use (email, newsletter) or leave them dormant — whichever is cleaner
- Add `deps.edn`, `bb.edn` or equivalent task runner
- Commit

**What you'll be able to test:**
- `clj -M:dev` (or whatever Biff's run command is) starts the app
- Open `http://localhost:8080` → see a page
- Kill with Ctrl-C, restart works cleanly

---

## Step 2 — XTDB round-trip from the REPL `[x]`

**Goal:** Prove we can write and read documents.

**What I'll do:**
- Add a `dev/user.clj` helper namespace with `save!` and `q` functions over the already-wired XTDB that Biff provides
- Add a `comment` block with sample calls

**What you'll be able to test:**
- From your editor's REPL, evaluate the forms in the comment block
- See a document round-trip
- See an `as-of` query return a prior version

---

## Step 3 — Entity schemas `[x]`

**Goal:** Define the shape of every entity the system will persist.

**What I'll do:**
- `src/robotfund/schema.clj` with `malli` schemas for `::candidate`, `::news-report`, `::analysis`, `::trade-proposal`, `::order`, `::fill`, `::llm-call`
- A `validate!` helper that throws on schema violations
- REPL examples in a comment block

**What you'll be able to test:**
- From the REPL, construct a valid `::candidate` — passes validation
- Construct an invalid one — get a readable error

---

## Step 3.5 — Migrate secrets to `~/fund/.env` (ADR 0005) `[x]`

**Why here:** Step 4 adds the first real API key (Alpaca). Per ADR 0005 and user request on 2026-04-21, do the migration *before* that so no real API key ever lives inside the git tree.

**What I'll do:**
- Create `~/fund/.env` from the current `./config.env` contents; `chmod 600 ~/fund/.env`
- Write `ops/dev.sh` that `source`s it, exports variables, and `exec`s `clj -M:dev dev`
- Delete the in-repo `config.env`
- Verify Biff tolerates missing `./config.env` (falls back to process env). Fallback if not: empty sentinel file or small patch
- Update `README.md` Quick Start to call `./ops/dev.sh`

**What you'll be able to test:**
- `./ops/dev.sh` → app boots on `:8080`, HTTP 200 on `/`
- `ls ./config.env` → not found
- `ls -l ~/fund/.env` → exists, mode `-rw-------`
- Ctrl-C, rerun `./ops/dev.sh` → clean restart

---

## Step 4 — Alpaca client (read-only) `[x]`

**Goal:** Talk to Alpaca paper trading from the REPL. No orders yet.

**What I'll do:**
- `src/robotfund/alpaca.clj` with `get-account`, `get-positions`, `get-bars`
- Credentials read from env vars; `.env.example` updated
- `clj-http` + `cheshire` added to `deps.edn`

**What you'll be able to test:**
- Put your Alpaca keys in `.env`
- REPL call `(alpaca/get-account)` → cash balance, equity, buying power
- REPL call `(alpaca/get-bars "AAPL" ...)` → recent price bars

---

## Step 5 — Anthropic client with `::llm-call` logging `[x]`

**Goal:** Ask Claude a question from the REPL and see the call persisted to XTDB.

**What I'll do:**
- `src/robotfund/llm.clj` with `(complete prompt opts)`
- Every call writes an `::llm-call` entity (prompt, model, response, latency, tokens)
- Defaults: Haiku for cheap calls, Sonnet for quality calls

**What you'll be able to test:**
- REPL: `(llm/complete "What is 2+2?" {})` → returns the answer
- REPL: query XTDB — see the `::llm-call` document

---

## Step 6 — Scanner agent (pure code, no LLM) `[x]`

**Goal:** First agent. Pulls bars for a small watchlist, emits `::candidate`s on simple triggers.

**What I'll do:**
- `src/robotfund/agents/scanner.clj`
- Hardcoded watchlist (5–10 mega-caps to start)
- Trigger: price change > 1.5% since prior close, or volume > 2× 20-day average
- `(run-scanner ctx)` — one cycle

**What you'll be able to test:**
- REPL: `(scanner/run-scanner ctx)` → some candidates appear in XTDB (or none, depending on the market)
- Query XTDB to see them

---

## Step 7 — News agent (first LLM agent) `[x]`

**Goal:** For each candidate, fetch news and score sentiment via LLM.

**What I'll do:**
- `src/robotfund/agents/news.clj`
- News source: **Alpaca's built-in news endpoint** (one fewer account to manage; switch later if limiting)
- LLM call summarizes + returns sentiment `-1..+1` as JSON
- Schema-validate before writing `::news-report`

**What you'll be able to test:**
- REPL: run scanner → run news agent → query XTDB
- See `::news-report` entries linked to candidates, with LLM reasoning stored in `::llm-call`

---

## Step 8 — Analyst agent `[x]`

**Goal:** Rate each candidate 1–10 with reasoning, given its news report.

**What I'll do:**
- `src/robotfund/agents/analyst.clj`
- Input: `::candidate` + `::news-report`
- LLM (Sonnet) returns JSON `{rating, reasoning, action}`
- Validate, write `::analysis`

**What you'll be able to test:**
- REPL: scanner → news → analyst
- Query a specific ticker — see the full chain: candidate → news-report → analysis, each referencing the prior

---

## Step 9 — Risk Manager `[x]`

**Goal:** Apply hard rules; approve, resize, or reject proposals.

**What I'll do:**
- `src/robotfund/agents/risk.clj`
- Pure Clojure rules (no LLM):
  - Max single position: 10% of equity
  - Max sector exposure: 30% (sector data from Alpaca asset metadata or a small hardcoded map)
  - Max trades per day: 5
  - Min analysis rating to buy: 7; to sell: ≤3
  - Never short
- Emit `::trade-proposal` with decision + reason

**What you'll be able to test:**
- REPL: pipe an `::analysis` through risk, see the proposal
- Craft an analysis that should be rejected (e.g. position would exceed cap) — confirm it's rejected with a readable reason

---

## Step 10 — Executor (real paper trades) `[x]`

**Goal:** Place approved proposals on Alpaca paper. End-to-end pipeline works manually.

**What I'll do:**
- `src/robotfund/agents/executor.clj`
- For each approved `::trade-proposal`: place order via Alpaca, write `::order`
- Poll and reconcile `::fill` (simple loop for now)
- Cancel open orders older than 30 min

**What you'll be able to test:**
- REPL: run full pipeline manually for one cycle
- Check Alpaca dashboard — see a real paper order placed
- Query XTDB — see the full chain candidate → news → analysis → proposal → order → fill

---

## Step 11 — Scheduler wiring `[x]`

**Goal:** Agents run automatically on a schedule during market hours.

**What I'll do:**
- `src/robotfund/schedule.clj`
- Biff scheduled tasks, staggered offsets (:00 Scanner, :02 News, :05 Analyst, :07 Risk, :10 Executor)
- Market-hours guard via `tick` (US/Eastern, 09:30–16:00, M–F, skip US market holidays)

**What you'll be able to test:**
- Start the app during market hours, watch logs — see each agent fire on schedule
- Outside market hours — see the guard skip them

---

## Step 12 — Dashboard v1: portfolio + timeline `[x]`

**Goal:** Open the browser, see what's happening.

**What I'll do:**
- `/` page: cash, positions, today's P&L, last cycle timestamp
- `/timeline` page: recent agent activity (last 100 events, newest first)
- Plain HTMX; no SSE yet

**What you'll be able to test:**
- Open `http://localhost:8080` → portfolio view renders
- Open `/timeline` → see agent events from XTDB
- Refresh to see updates after each cycle

---

## Step 13 — Dashboard v2: live updates + trade drilldown `[x]`

**Goal:** Dashboard updates without refresh. Click into a trade to see the full chain.

**What I'll do:**
- SSE endpoint pushing new events
- Timeline page consumes SSE via HTMX ext
- `/trade/:id` — candidate → news → analysis → proposal → order → fill, with expandable LLM prompt/response for each

**What you'll be able to test:**
- Open `/timeline`, leave it open
- Trigger a cycle from REPL — watch new events flow in live
- Click a trade → see the full reasoning trail including LLM prompts

---

## Step 14 — Containerize `[ ]`

**Goal:** Runs in Podman exactly like on the host.

**What I'll do:**
- `Containerfile` — multi-stage: builder makes the uberjar, runtime is a slim JRE image
- Non-root user owning `/data`
- `Makefile` or `bb` tasks for `build`, `run`, `stop`

**What you'll be able to test:**
- `make build && make run` (or equivalent) — app responds on `localhost:8080`
- Same REPL-style verification works via a running container

---

## Step 15 — Mac mini deployment (bind mount + launchd) `[ ]`

**Goal:** Fund runs unattended on the Mac mini, survives reboots and container deletion. Matches ADR 0004.

**What I'll do:**
- `podman run` command with `-v ~/fund/xtdb-data:/data/xtdb -v ~/fund/logs:/data/logs --env-file ~/fund/.env`
- `ops/com.robotfund.plist` launchd template + install script
- Short `docs/runbook.md` covering start/stop/logs/backups

**What you'll be able to test:**
- Run the container, trade a few cycles
- `podman rm -f robotfund`, start a new container pointed at same volume — see XTDB state survive
- Reboot Mac mini — within ~2 minutes the container is back, dashboard responding

---

## Step 16 — Hardening `[ ]`

**Goal:** The fund shrugs off transient failures during the trading week.

**What I'll do:**
- Kill-switch env var: `SIMULATE_ONLY=1` — Executor logs intended orders but never calls Alpaca
- Retry wrapper for all external HTTP (3 attempts, exponential backoff)
- LLM call timeout 30s → skip candidate this cycle; Alpaca timeout 10s → retry, then skip
- `/health` endpoint returning `200` + last-cycle timestamp

**What you'll be able to test:**
- Set `SIMULATE_ONLY=1` → pipeline runs, no Alpaca orders placed
- Temporarily give Alpaca a bad key → see retries, then graceful skip in logs, fund still alive
- Hit `/health` — confirm uptime monitor will work

---

## Step 17 — Dry run (2026-05-11 → 2026-05-17) `[ ]`

**Goal:** 7 days unattended before it counts.

**What I'll do:**
- External uptime check (e.g. a simple cron on a free service hitting `/health`)
- Verify Anthropic monthly spend cap is active
- Daily morning/evening dashboard glance checklist in `docs/runbook.md`

**What you'll be able to test:**
- Leave the fund alone for a week
- Each day: confirm it's still trading, nothing weird in the timeline
- End of dry run: decide if anything needs a tiny fix before May 18

---

## Post-trading (after 2026-05-25)

Not yet detailed — will plan once we see how the trading week goes. Likely shape:
- Snapshot/export final XTDB state
- Prepare the 15–20 min presentation (architecture walkthrough + live bitemporal query demo + ADR trail)
- Rehearse

---

## Open decisions (to make at the right step)

- **Watchlist composition** (Step 6) — mega-caps only, or include a few volatile names? Start conservative, expand later.
- **News source** (Step 7) — Alpaca's built-in first; switch to Finnhub/NewsAPI if Alpaca's depth is insufficient.
- **Sector data source** (Step 9) — Alpaca asset metadata, or a hand-curated ticker→sector map? Start hardcoded for the watchlist.
- **Scheduler cadence** (Step 11) — 15 min is a starting point; might widen to 30 or narrow to 10 after dry run observation.

## Cross-cutting conventions

- Every commit implements exactly one step. Commit message: `Step N: <step title>`.
- Every LLM call writes an `::llm-call` entity — no exceptions. This is the audit trail the challenge rewards.
- Every external HTTP call goes through the retry wrapper (from Step 16 onward).
- Hard constraints live in Clojure, not in LLM prompts (per ADR 0003).

## Living status log

Newest on top.

- 2026-04-23 — Step 13 done: WS live updates on /timeline (on-tx broadcasts new rows via hx-swap-oob); /trade/:id drilldown shows full candidate→news→analysis→proposal→order→fill chain with expandable LLM prompt/response via <details>; "view →" links on RISK/ORDER rows in timeline; dashboard-clients atom wired to initial-system
- 2026-04-23 — Step 12 done: dashboard.clj with / (portfolio: equity/cash/P&L/positions from Alpaca, last scan time) and /timeline (last 100 XTDB events, all 6 agent types, newest first); home / route moved to /login to free root path
- 2026-04-23 — Step 11 done: schedule.clj with market-hours guard (ET, 09:30–16:00, M–F, 2026 NYSE holidays), five agent tasks staggered at +0/+2/+5/+7/+10 min within 15-min cycle; pure java.time, no extra deps
- 2026-04-23 — Dynamic scanner: replaced static 10-ticker watchlist with Alpaca screener (movers + most-actives); min-price $5 filter eliminates warrants/penny stocks; fallback watchlist retained for when screener is unavailable
- 2026-04-23 — Step 10 done: Executor places Alpaca market orders for approved proposals, polls fills (3s), reconciles/cancels stale pending orders; alpaca/place-order + get-order + cancel-order added; bb execute + bb pipeline tasks added
- 2026-04-23 — Step 9 done: Risk Manager applies hard Clojure rules (max 10% position, 30% sector, 5 trades/day, min rating 7 to buy, max 3 to sell, no shorting), writes :trade-proposal; schema updated to make quantity optional on rejected proposals; bb risk + bb proposals tasks added
- 2026-04-23 — Step 8 done: Analyst agent rates each candidate 1–10, recommends buy/sell/hold via Gemini, writes :analysis to XTDB; bb analyse + bb analyses tasks added
- 2026-04-23 — Step 7 done: News agent fetches Alpaca news, scores sentiment via Gemini, writes :news-report linked to :candidate and :llm-call; llm/complete now returns {:text :llm-call-id}; retry covers 429+503
- 2026-04-23 — Step 6 done: Scanner agent with 10-ticker watchlist, price-change >1.5% and volume-spike >2× triggers, candidates persisted to XTDB; bb.edn + ops/nrepl-eval.bb added for manual testing
- 2026-04-22 — Step 5 done: Gemini LLM client with 429 retry, xt/await-tx, :llm-call logged to XTDB
- 2026-04-22 — Step 4 done: Alpaca client with get-account, get-positions, get-bars (data API on data.alpaca.markets)
- 2026-04-21 — Step 3.5 done: secrets migrated to ~/fund/.env; ops/dev.sh replaces clj -M:dev dev
- 2026-04-21 — Step 3 done: entity schemas (candidate, news-report, analysis, trade-proposal, order, fill, llm-call) + validate! helper
- 2026-04-21 — Step 2 done: dev/user.clj with save!, q, and as-of comment block
- 2026-04-20 — Step 1 done: Biff v1.9.1 skeleton boots on :8080
- 2026-04-20 — Plan created
