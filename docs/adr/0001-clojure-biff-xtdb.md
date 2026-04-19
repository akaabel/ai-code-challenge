# ADR 0001: Clojure + Biff + XTDB

**Status:** Accepted · **Date:** 2026-04-19

## Context

The challenge allows any tech choice. The dominant AI-agent ecosystem is Python (LangGraph, CrewAI, AutoGen). A choice is needed before any code is written.

## Decision

Use **Clojure** on the JVM with the **Biff** web framework, which bundles:
- **XTDB** — bitemporal document database
- **HTMX** — server-rendered UI with live interactivity (no SPA build pipeline)
- Built-in auth, scheduled tasks, background jobs
- Single uberjar deployment

LLM orchestration will be built from scratch (no existing Clojure framework equivalent to LangGraph).

## Alternatives Considered

| Option | Why not |
|---|---|
| **Python + LangGraph/CrewAI** | Richer agent ecosystem, but the framework hides the architecture we want to *show* judges. Also weaker story for bitemporal traceability. |
| **TypeScript + Next.js** | Unified language, widely known, but no bitemporal DB in ecosystem; would need to build audit trail manually. |
| **Clojure + Reagent/Re-frame SPA** | More interactive dashboard, but doubles the build complexity (CLJS build pipeline) for marginal UX gains in a 15-min demo. |
| **Clojure + raw Ring/Reitit** | More DIY than Biff; saves nothing meaningful and costs setup time. |

## Rationale

- **REPL-driven development** is ideal for iterating on trading logic — reload agents without restarting, inspect XTDB live.
- **Immutable data + functional composition** naturally fit a pipeline of pure-ish agent stages that each take inputs and produce outputs.
- **XTDB bitemporal is the killer feature for this challenge.** The traceability requirement ("show how agents reasoned, what they knew when") becomes a one-line `as-of` query instead of a custom audit-log schema. Directly supports the Best Architecture prize criterion.
- **Biff's batteries-included** (scheduler, auth, single uberjar) removes yak-shaving and leaves more time for agent design — which is where the prize money is.
- **HTMX over CLJS SPA:** eliminates the ClojureScript build pipeline; SSE gives live updates for the demo dashboard; any remaining interactivity needs can drop in a tiny bit of CLJS on specific pages.
- **No mature Clojure LLM framework is a feature, not a bug.** Building orchestration from scratch (~100–200 LOC) makes the architecture *visible* to judges rather than hidden behind a framework.

## Tradeoffs Accepted

- Must implement agent orchestration, scheduling boundaries, and LLM call plumbing ourselves (mitigation: small code surface, directly aligned with prize criteria).
- No official Clojure Alpaca SDK; use `clj-http` against the REST API (the API is small and well-documented).
- Smaller community = fewer copy-pasteable examples for trading-specific code. Acceptable for a challenge with no production constraints.
