# ADR 0004: XTDB Persistence via Host Bind Mount

**Status:** Accepted · **Date:** 2026-04-20

## Context

The container is intentionally ephemeral — we expect to rebuild and restart it many times during the build week and potentially during the trading week itself. XTDB state (the entire reasoning trail the challenge asks us to preserve) must survive container deletion. The persistence approach also needs to support backup via Time Machine and be easy to inspect during live debugging.

## Decision

Bind-mount host directories under the user's home into the container at known paths:

| Host path | Container path | Purpose |
|---|---|---|
| `~/fund/xtdb-data/` | `/data/xtdb/` | XTDB log, index, and checkpoint files |
| `~/fund/logs/` | `/data/logs/` | Structured JSON log files for post-mortem analysis |

The container itself holds **no state**. Deleting it has zero data impact. A new container pointing at the same host directories resumes trading transparently.

Run-command shape (for reference once we get to implementation):

```bash
podman run \
  -v ~/fund/xtdb-data:/data/xtdb \
  -v ~/fund/logs:/data/logs \
  -p 8080:8080 \
  --env-file ~/fund/.env \
  robotfund:latest
```

## Alternatives Considered

| Option | Why not |
|---|---|
| **Named Podman volume** (`-v xtdb-data:/data/xtdb`) | Lives inside the Podman Machine VM on macOS, so Time Machine does not see it. Backup requires scheduled `podman volume export`. Inspection requires `podman volume inspect` instead of `ls`. No meaningful benefit over a bind mount here. |
| **External DB (Postgres, etc.)** | Adds an operational moving part for no benefit; local-disk XTDB is fine for a $100k paper-trading fund. |
| **Persist in the container + periodic `podman commit`** | Defeats the point of immutable images. Painful rollback story. |
| **In-memory only** | Obviously wrong — loses the audit trail on any restart. |

## Rationale

- **Time Machine "just works"** — paths under `/Users/` are backed up automatically. If the Mac mini dies mid-trading-week, XTDB state can be restored to another machine with a clone + `podman run`.
- **Inspection is trivial** — `ls`, `du`, `tail`, any standard Unix tool works on the data dir directly. Important when debugging during the trading window.
- **macOS Podman auto-shares paths under `/Users/`** into the Podman Machine VM; no extra `podman machine init --volume` configuration needed.
- **Portability** — bind mounts are a standard Docker/Podman convention; anyone who clones the repo and has a container runtime can point at their own directory.
- **Explicit location beats managed storage** — `~/fund/xtdb-data/` is self-documenting and easier to explain in the conference demo than "Podman is managing it somewhere in a VM."

## Tradeoffs Accepted

- **Permissions coupling.** The container's UID must be able to write the host directory. Rootless Podman on macOS usually handles this transparently; verify on first run and `chown` if needed.
- **No atomic snapshot.** A naive `cp -r` while the container is running could yield an inconsistent copy. Mitigation: the backup script (if we write one) stops the container briefly, copies, restarts. For the trading window, Time Machine's hourly snapshots during quiet periods are sufficient — and XTDB's log-structured storage tolerates torn reads reasonably well.
- **Path hard-coded in run command.** Acceptable; will live in the project's `Makefile` / `justfile` once that exists.
