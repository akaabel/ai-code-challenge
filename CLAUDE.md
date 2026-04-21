# AddNode Agent AI Challenge 2026

## Project

AI-agent robot fund for the AddNode Architecture Group challenge. A system of autonomous AI agents that manage a $100k stock portfolio via Alpaca paper trading (NYSE/NASDAQ).

**Goal:** Build, run, and present the fund at the May 2026 conference.

## Key Dates

- **May 18:** Automated trading starts (all funds go live)
- **May 25, 15:00:** Portfolio value is read (deadline)
- **May conference:** Presentation (15-20 min) and awards

## Challenge Requirements

- At least one autonomous AI agent making trade decisions
- Alpaca paper trading API integration
- Logging/traceability of agent reasoning and design decisions
- Free tech choice

## Bonus Targets

- LLM-driven buy/sell decisions
- Dashboard showing portfolio status + agent activity
- Visualization of agent collaboration (timeline of decisions)

## Prizes

- **Best Agent Architecture** -- main prize, thoughtful multi-agent design
- **The Fund Manager** -- best portfolio return
- **MacGyver Prize** -- most creative solution

## Platform

- **Alpaca Paper Trading** (alpaca.markets) -- free sandbox, REST API
- Market: NYSE / NASDAQ
- Starting capital: $100,000 USD

## Tech Stack

Clojure + Biff framework (XTDB bitemporal DB, HTMX dashboard) · Podman container on Mac mini (24/7) · Anthropic Claude for LLM judgment · Alpaca REST API via `clj-http`.

## Commands

_To be filled in once `deps.edn` and `Containerfile` exist._

## Documentation

- **`docs/implementation-plan.md`** -- step-by-step build plan; active work tracker. **Start here.**
- **`docs/architecture.md`** -- living system design (agent roles, data flow, observability)
- **`docs/adr/`** -- Architecture Decision Records (frozen decisions with rationale)

## Claude Code
- When done with a task, suggest a git commit message, but do not commit it. I will do that myself.
