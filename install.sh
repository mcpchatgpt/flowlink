#!/usr/bin/env bash
set -euo pipefail

if [ "${EUID}" -ne 0 ]; then
  echo "Run as root: sudo ./install.sh"
  exit 1
fi

ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
if [ -x "$ROOT_DIR/server/install.sh" ]; then
  exec "$ROOT_DIR/server/install.sh"
fi

REPOSITORY=${FLOWLINK_REPOSITORY:-mcpchatgpt/flowlink}
REF=${FLOWLINK_REF:-main}
TEMP_DIR=$(mktemp -d /tmp/flowlink-install.XXXXXX)
trap 'rm -rf "$TEMP_DIR"' EXIT
HEADER_ARGS=(-H "Accept: application/vnd.github+json")
if [ -n "${FLOWLINK_GITHUB_TOKEN:-}" ]; then
  HEADER_ARGS+=(-H "Authorization: Bearer ${FLOWLINK_GITHUB_TOKEN}")
fi
curl -fsSL --retry 3 "${HEADER_ARGS[@]}" \
  "https://api.github.com/repos/${REPOSITORY}/tarball/${REF}" \
  | tar -xz --strip-components=1 -C "$TEMP_DIR"
"$TEMP_DIR/server/install.sh"
