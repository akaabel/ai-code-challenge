# ADR 0005: Secrets in a Single Out-of-Tree Env File

**Status:** Accepted · **Date:** 2026-04-21

## Context

Biff's starter places `config.env` at the project root, gitignored, holding cookie/JWT secrets and any API keys we add. It works — but the file lives *inside* the git tree, so a single `git add -A`, misconfigured editor plugin, or global `git` alias is one step away from committing secrets.

As the project grows it'll accumulate real secrets: `COOKIE_SECRET`, `JWT_SECRET`, `ALPACA_KEY_ID`, `ALPACA_SECRET`, `ANTHROPIC_API_KEY`, possibly news API keys. The blast radius of an accidental commit grows with each one.

ADR 0004 already names `~/fund/.env` as the env-file the Podman container reads at start-up. That's a non-repo location we're going to have anyway. Making dev use the same file gives one source of truth across both environments.

## Decision

- **Single secrets file at `~/fund/.env`**, physically outside the git tree. Holds *all* environment-dependent config: true secrets (`COOKIE_SECRET`, `ALPACA_SECRET`, `ANTHROPIC_API_KEY`, …) plus plain env config (`NREPL_PORT`, `XTDB_TOPOLOGY`, `DOMAIN`) that varies between host and container.
- **Dev loads it via a wrapper script**: `ops/dev.sh` sources `~/fund/.env`, exports its variables, and `exec`s `clj -M:dev dev`. You run `./ops/dev.sh` instead of `clj -M:dev dev` directly.
- **Container loads it via `--env-file ~/fund/.env`** (already decided in ADR 0004).
- **No `config.env` under the repo.** The Biff-default file is deleted at implementation time. No Biff framework changes are required — `#biff/secret` reads from the process environment regardless of where the env originated.
- **`resources/config.template.env` is retained** as the documented schema of variables a new contributor must populate in their `~/fund/.env`.

## Alternatives Considered

| Option | Why not |
|---|---|
| **In-repo `config.env` gitignored** (Biff default) | Works, but the file exists inside the git tree. Structural safety beats policy safety — a fence is stronger than a sign. |
| **macOS Keychain + launcher shelling out to `security`** | Stronger at-rest encryption than a FileVault-only file, but adds per-secret ceremony (`security add-generic-password …` for each) and a wrapper that forks `security` N times per launch. Marginal security win vs. an out-of-tree file on a FileVault disk. Easy upgrade path later if we change our minds. |
| **Exports in `~/.zshrc`** | Just moves the plaintext file to a less-appropriate location with wider reach (sourced by every shell) and easier to leak via screen-share. |
| **Secrets manager (Vault, 1Password CLI, SOPS)** | Real setup cost for a single-developer, single-host, time-boxed challenge. |

## Rationale

- **Structural guarantee.** The file cannot be `git add`-ed because it is not under the git tree. This is categorically stronger than "the `.gitignore` will catch it."
- **One file, two consumers.** Dev wrapper `source`s it; Podman reads it via `--env-file`. Same names, same values, no drift between environments.
- **Aligns with ADR 0004.** We were already going to have `~/fund/.env` for the container; this just uses it for dev too.
- **Dev workflow stays one command.** `./ops/dev.sh` replaces `clj -M:dev dev`. Mental cost ~0.
- **Scales with the project.** Every new API key from Step 4 onward drops into the same file in the same place.

## Implementation Notes

- **Biff `config.env` fallback.** Verify at implementation time that Biff tolerates a missing `./config.env` and just uses the process environment. Expected: yes. If Biff hard-fails, the fallback is either an empty sentinel `config.env` in the repo or a small patch to `use-aero-config`.
- **Permissions.** `chmod 600 ~/fund/.env` on creation — owner-read/write only, the standard for env-files.
- **When to implement.** Any time; natural fits are (a) right before Step 4 (first real API key lands) so Alpaca keys go straight into `~/fund/.env`, or (b) Step 15 (Mac mini deployment) when `~/fund/.env` is created for the container anyway.

## Tradeoffs Accepted

- **At-rest encryption is only FileVault-deep.** Same as today's `config.env` on the same disk. If we later want per-file encryption, swapping `ops/dev.sh` to pull from Keychain is a ten-minute change.
- **One extra command to remember** (`./ops/dev.sh`). Offset by a `Makefile` target or shell alias if it becomes annoying.
- **Non-secret env config coexists with secrets** in a single file. Mildly mushy conceptually but operationally simplest; not worth a second file for a project this size.
