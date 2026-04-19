# Architecture

Living design document for the robot fund. Updated as the system evolves. For *decisions* with rationale (why we chose X over Y), see [`adr/`](./adr/).

## 1. System Overview

A pipeline of specialized AI agents that collaboratively manage a $100k paper trading portfolio on Alpaca. Each agent is a scheduled Clojure function that fetches data, consults an LLM for qualitative judgment, validates the result, and writes its output to XTDB. The next agent in the pipeline reads that output and repeats.

```
    ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
    │   Market     │──▶│    News /    │──▶│   Analyst    │──▶│     Risk     │──▶│   Executor   │
    │   Scanner    │   │  Sentiment   │   │              │   │   Manager    │   │  (Portfolio  │
    │              │   │              │   │              │   │              │   │   Manager)   │
    └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘   └──────────────┘
           │                  │                  │                  │                  │
           ▼                  ▼                  ▼                  ▼                  ▼
    ┌──────────────────────────────────────────────────────────────────────────────────────┐
    │                              XTDB (bitemporal event store)                           │
    └──────────────────────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼
                                      ┌──────────────┐
                                      │   Dashboard  │
                                      │  (Biff/HTMX) │
                                      └──────────────┘
```

Every agent writes to XTDB with both **valid-time** (when the fact was true in the world) and **transaction-time** (when the agent learned it). This is what makes "what did the Risk Manager know at 14:32?" a one-line query.

## 2. Agent Roles

| Agent | Purpose | Inputs | Outputs (to XTDB) |
|---|---|---|---|
| **Market Scanner** | Identify candidate stocks showing interesting activity | Price/volume snapshots from Alpaca | `::candidate` entities with ticker + trigger reason |
| **News / Sentiment** | Summarize recent news and sentiment per candidate | Candidates + news API results | `::news-report` with summary + sentiment score |
| **Analyst** | Rate each candidate 1–10 with reasoning | Candidate + news report + fundamentals | `::analysis` with rating, reasoning, target action |
| **Risk Manager** | Enforce hard constraints, veto or size trades | Analysis + current portfolio + risk rules | `::trade-proposal` (approved/rejected/resized) |
| **Executor (Portfolio Manager)** | Place orders via Alpaca, reconcile fills | Approved trade proposals | `::order` + `::fill` records |

Each agent is scheduled via Biff's built-in scheduler (e.g. every 15 minutes during market hours). Agents communicate *only* via XTDB — no direct calls between them.

## 3. How Agents Work

An "AI agent" here is not a separate running process. It is a **scheduled Clojure function** that, when invoked, performs one cycle:

1. **Fetch** — pull the inputs it needs from XTDB and external APIs (Alpaca, news feeds).
2. **LLM call** — construct a prompt with the fetched data, ask the LLM for a qualitative judgment, request structured JSON output.
3. **Parse & validate** — parse the JSON, validate it against a `malli` schema, reject malformed responses.
4. **Persist** — write the result to XTDB along with the prompt + raw response (for replay and audit).
5. **Trigger** — optionally signal the next stage (or rely on the next stage's own schedule).

Concrete example — the Analyst for ticker `AAPL`:

```
[Clojure]  fetches AAPL price, volume, news summary, fundamentals from XTDB
    │
    ▼
[LLM call] "You are a financial analyst. Given {data}, rate AAPL 1-10 and
            recommend {buy, hold, sell}. Return JSON: {rating, reasoning, action}."
    │
    ▼
[LLM resp] {"rating": 7, "reasoning": "Strong earnings, supply chain...", "action": "buy"}
    │
    ▼
[Clojure]  validates schema, stores ::analysis entity in XTDB
```

The LLM does the *judgment*. Clojure does everything else. This is deliberate — see §5.

## 4. Pipeline Data Flow

| Stage | Reads from XTDB | Writes to XTDB | External calls |
|---|---|---|---|
| Scanner | (current portfolio, watchlist) | `::candidate` | Alpaca market data |
| News | recent `::candidate` | `::news-report` | News API + LLM |
| Analyst | `::candidate` + `::news-report` | `::analysis` | LLM |
| Risk | `::analysis` + current positions | `::trade-proposal` | LLM (optional) |
| Executor | approved `::trade-proposal` | `::order`, `::fill` | Alpaca orders |

A full cycle produces a traceable chain: candidate → news report → analysis → trade proposal → order → fill. Every step has an XTDB document linked to the prior one, making the full reasoning trail reconstructable.

## 5. LLM-vs-Code Separation

**LLM handles (qualitative, subjective):**
- Reading news narratives and extracting sentiment
- Weighing conflicting signals
- Rating and ranking candidates
- Free-form reasoning to explain a decision

**Clojure handles (hard, precise):**
- Scheduling and orchestration
- Data fetching and transformation
- JSON schema validation
- Portfolio math (position sizing, P&L, exposure)
- Risk guardrails (max position size, sector caps, daily loss limits, max trades per day)
- Alpaca API calls (orders, reconciliation)
- Persistence

**Why this split:** LLMs are unreliable at math, expensive per call, and occasionally hallucinate. Clojure is cheap, fast, and exact. Putting hard constraints in code means the LLM physically *cannot* violate them — a malformed or malicious response is rejected before any order is placed.

## 6. Observability

The core challenge requirement is to show "how agents reasoned and what decisions they made." XTDB's bitemporal model makes this natural:

- Every LLM call is persisted as an `::llm-call` entity with prompt, raw response, parsed output, and timestamp.
- Every agent-produced entity (`::analysis`, `::trade-proposal`, etc.) references the `::llm-call` that produced it.
- Bitemporal queries: "As of 14:32 yesterday, what analyses existed for AAPL?" → single XTDB `as-of` query.
- Dashboard timeline: derived from the transaction log, renders agent activity as a scrollable feed ("16:32 — News agent flags Apple report → Analyst rates 7/10 → Risk approves → Executor buys 50 AAPL").

## 7. Deployment Topology

- **Single uberjar** built from Clojure source + Biff framework.
- **Podman container** wrapping the uberjar, exposing port 8080 (dashboard) and persisting XTDB state to a volume.
- **Host:** Mac mini, running 24/7, auto-starts the container on boot via `launchd` plist.
- **External services:** Alpaca paper trading API, Anthropic API, one or more news APIs. Credentials via environment variables injected into the container.
- **Backups:** XTDB volume snapshotted daily to the Mac mini's Time Machine drive.
