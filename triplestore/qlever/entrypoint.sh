#!/bin/bash
# Starts QLever for Articulorum (docker compose service "qlever").
#
# QLever answers queries from an index built ahead of time and applies SPARQL updates on top of
# it. Articulorum fills the store at run time (the triplestore service mirrors every LDP resource
# into a named graph), so on first start an index is built from a one-triple seed file; afterwards
# the existing index is reused and updates are persisted next to it (--persist-updates).
#
# Environment:
#   QLEVER_ACCESS_TOKEN   token required for updates (default articulorum-qlever)
#   QLEVER_MEMORY         memory for query processing (default 2G)
#   QLEVER_PORT           HTTP port (default 7001)
set -Eeuo pipefail

DATA=/data
NAME="${DATA}/articulorum"
PORT="${QLEVER_PORT:-7001}"

# Verify that the image's native QLever binaries are accessible.
for binary in qlever-index qlever-server; do
    if ! command -v "$binary" >/dev/null 2>&1; then
        echo "ERROR: Required QLever executable not found: $binary" >&2
        echo "PATH=$PATH" >&2
        echo "Contents of /qlever:" >&2
        ls -la /qlever >&2 || true
        exit 1
    fi
done

mkdir -p "${DATA}"
cd "${DATA}"

# Build the initial index only when one does not already exist.
if ! compgen -G "${NAME}.index.*" >/dev/null; then
    echo "Building initial QLever index: ${NAME}"

    if [[ ! -f /opt/articulorum-qlever/seed.nq ]]; then
        echo "ERROR: Missing seed file." >&2
        exit 1
    fi

    qlever-index \
        -i "${NAME}" \
        -F nq \
        -f /opt/articulorum-qlever/seed.nq
else
    echo "Existing QLever index found: ${NAME}"
fi

echo "Starting QLever on port ${PORT}"

exec qlever-server \
    -i "${NAME}" \
    -p "${PORT}" \
    -m "${QLEVER_MEMORY:-2G}" \
    -a "${QLEVER_ACCESS_TOKEN:-articulorum-qlever}" \
    --persist-updates
