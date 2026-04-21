# robotfund

AI-agent robot fund for the AddNode Architecture Group Agent AI Challenge 2026.

## Quick start

1. Create `~/fund/.env` from [`resources/config.template.env`](./resources/config.template.env) and populate the secrets.
2. Run the app:

```sh
./ops/dev.sh
```

Then open <http://localhost:8080>.

A few harmless startup warnings are expected — see [`docs/known-warnings.md`](./docs/known-warnings.md).

## Where to look

- [`CLAUDE.md`](./CLAUDE.md) — project overview, dates, constraints
- [`docs/implementation-plan.md`](./docs/implementation-plan.md) — step-by-step build plan (start here)
- [`docs/architecture.md`](./docs/architecture.md) — system design
- [`docs/adr/`](./docs/adr/) — architecture decision records

## Stack

Clojure · [Biff](https://biffweb.com) · XTDB · HTMX · Alpaca paper trading · Anthropic Claude · Podman on Mac mini.
