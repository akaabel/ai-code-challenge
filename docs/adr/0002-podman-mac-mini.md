# ADR 0002: Podman on Mac mini

**Status:** Accepted · **Date:** 2026-04-19

## Context

The trading window is May 18 through May 25 at 15:00. The fund must run unattended the entire week, including nights and weekends. We need a deployment target that is reliably up for ~7 days.

## Decision

Run the whole system in a **single Podman container** on the user's **Mac mini**, which is already owned and capable of running 24/7.

The container:
- Wraps the Biff uberjar
- Exposes port 8080 (dashboard)
- Mounts a persistent volume for XTDB state
- Receives secrets (Alpaca keys, Anthropic key, news API keys) via environment variables
- Auto-starts on boot via `launchd` plist so a reboot (OS update, power blip) resumes trading

## Alternatives Considered

| Option | Why not |
|---|---|
| **Cloud VM (Hetzner, Fly.io, Railway)** | Recurring cost and extra ops setup for a one-week window, with no real benefit over the Mac mini which is already up. |
| **Raspberry Pi at home** | Underpowered for a JVM + XTDB workload; no material upside vs. Mac mini. |
| **Run directly on host, no container** | Loses reproducibility and dependency isolation. Harder to demo on another machine at the conference. |
| **Docker Desktop instead of Podman** | Requires a running VM and Docker Desktop license nuances on macOS. Podman Machine is equally fine and daemonless / rootless. |

## Rationale

- **Zero recurring cost** — Mac mini is owned and already running.
- **Container = reproducibility** — `podman run` reproduces the exact environment on any Linux/macOS box, which matters for the conference demo.
- **Podman on macOS** is solid today, rootless by default, no daemon, drop-in for Docker CLI if needed.
- **Single container, single volume, single uberjar** is the simplest thing that can possibly work for a week-long trading run.

## Tradeoffs Accepted

- **No HA / failover.** If the Mac mini dies, the fund stops trading. Acceptable — this is paper trading for a challenge, not real money, and a Mac mini is reliable hardware.
- **Home internet is the network dependency.** An outage would pause trading. Mitigation: auto-restart container on boot; if needed, tether to cellular as fallback during the trading week.
- **macOS updates can force reboots.** Mitigation: disable automatic updates during May 18–25.
