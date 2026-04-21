#!/usr/bin/env bash
set -euo pipefail

ENV_FILE="$HOME/fund/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Error: $ENV_FILE not found." >&2
  echo "Create it from resources/config.template.env — see README." >&2
  exit 1
fi

# Export all vars from the env file into this process, then hand off to clj.
set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

exec clj -M:dev dev
