# ADR 0003: Pipeline Agent Pattern with LLM-in-the-Loop

**Status:** Accepted · **Date:** 2026-04-19

## Context

"AI agent" covers a wide design space. Choices range from a single autonomous LLM loop with tools, to a swarm of debating agents, to a deterministic pipeline where each stage calls an LLM for one specific judgment. This choice shapes the whole system and determines how the architecture reads to judges.

## Decision

Use a **deterministic pipeline** of specialized agents:

```
Scanner → News/Sentiment → Analyst → Risk Manager → Executor
```

Each agent is a **scheduled Clojure function + one specialized LLM call + XTDB read/write**. Agents communicate only through XTDB — no direct calls.

Apply a strict **LLM/code split**:
- **LLM:** qualitative judgment, narrative reading, rating, reasoning
- **Clojure:** scheduling, data fetching, JSON validation, portfolio math, risk guardrails, API calls, persistence

## Alternatives Considered

| Option | Why not |
|---|---|
| **Single autonomous agent loop** (one LLM + tools, decides next action in a loop) | Most "agentic" feel, but hard to constrain, hard to demo, hard to trace, and unclear agent-role separation. Weak on Best Architecture prize criterion. |
| **Pure rule-based bots** (no LLM) | Fails the LLM-driven bonus and misses the core learning goal of the challenge. |
| **Swarm / debate pattern** (bull vs bear argue, judge decides) | Interesting for MacGyver prize as a *twist*, but risky as the primary architecture — hard to keep focused, expensive, unpredictable during the week-long run. |
| **Event-driven choreography** (agents react to XTDB changes via listeners) | More elegant but harder to reason about timing and cost. Scheduler-driven pipeline is more predictable for a fund that must run unattended for a week. |

## Rationale

- **Clear agent roles and responsibilities** — directly targets the Best Architecture prize. Each agent's job, inputs, and outputs are obvious in one glance.
- **Matches the challenge's own example timeline verbatim:** *"16:32 — News agent flags Apple report → Analyst rates positive → Portfolio manager buys 50 AAPL."*
- **Every stage is independently loggable, replayable, testable.** The LLM call for each stage can be pinned and re-run against a frozen XTDB state.
- **Predictable cost and cadence.** N LLM calls per cycle, M cycles per day — easy to budget and observe. Agent loops have unbounded cost in pathological cases.
- **LLM focuses on its one strength per stage.** Keeps prompts short, reduces drift, improves output quality.

## LLM/Code Split — Principle

Putting hard constraints in Clojure code means the LLM physically *cannot* break them. Even a malformed, hallucinated, or adversarial LLM response is rejected at the validation boundary before any order reaches Alpaca.

- **LLM:** "Is Apple's latest earnings report bullish?" — the kind of question humans ask analysts.
- **Clojure:** "Would buying 50 AAPL push our tech-sector exposure above 40%?" — the kind of question you ask a spreadsheet.

## Tradeoffs Accepted

- **Less emergent behavior** than an autonomous agent loop. Acceptable — we want legibility and reliability for a week-long unattended run.
- **Fixed number of pipeline stages** per cycle. Acceptable — adding a stage is a small refactor, not an architectural change.
- **Scheduler cadence vs market events.** Fixed-interval scheduling may miss fast-moving news. Acceptable for a week-long challenge; a future ADR could add event-driven triggers on top of the pipeline.
