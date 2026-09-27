#!/usr/bin/env bash
# Install the fork's git hooks into this clone. Upstream's .githooks/ are upstream-owned and are
# deliberately not installed (they enforce upstream's own process).
set -euo pipefail
root=$(git rev-parse --show-toplevel)
for hook in commit-msg pre-commit; do
  install -m 755 "$root/scripts/fork/githooks/$hook" "$root/.git/hooks/$hook"
done
echo "Installed commit-msg and pre-commit from scripts/fork/githooks/."
