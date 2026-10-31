#!/usr/bin/env bash
# Entry point of the e2e compose service (image cypress/included): installs the test project's
# packages into the container's own node_modules volume, forwards localhost:8000 to the gateway,
# waits for the user interface and the platform, then runs Cypress.
#
# Environment: E2E_BROWSER (chrome), E2E_VIDEO (false), E2E_ARGS (extra "cypress run" arguments),
# E2E_FORWARDS (8000:gateway:9000; space separated <local port>:<host>:<port> mappings).
set -euo pipefail
cd /e2e

# packages for this OS, reinstalled only when the lock file changes
lock_hash="$(sha256sum package-lock.json | cut -d' ' -f1)"
if [[ ! -f node_modules/.lock-hash || "$(cat node_modules/.lock-hash)" != "$lock_hash" ]]; then
  npm ci --no-audit --no-fund
  echo "$lock_hash" > node_modules/.lock-hash
fi

# shellcheck disable=SC2086
node docker/forward.mjs ${E2E_FORWARDS:-8000:gateway:9000} &
forwarder=$!
trap 'kill "$forwarder" 2>/dev/null || true' EXIT

node docker/wait.mjs --timeout=300 http://localhost:8000/ui/ http://localhost:8000/platform/

# shellcheck disable=SC2086
npx cypress run --browser "${E2E_BROWSER:-chrome}" --config "video=${E2E_VIDEO:-false}" ${E2E_ARGS:-}
