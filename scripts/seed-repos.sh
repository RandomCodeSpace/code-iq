#!/usr/bin/env bash
# Clone reference repos at pinned commits into ./.seeds/<name>.
# Idempotent: existing .seeds/<name> at the correct commit is reused.
set -euo pipefail

LOCK_FILE="${LOCK_FILE:-scripts/seed-repos.lock}"
SEED_DIR="${SEED_DIR:-.seeds}"

mkdir -p "$SEED_DIR"

while IFS= read -r line; do
  # skip blank / comment lines
  [[ -z "$line" || "$line" =~ ^# ]] && continue
  read -r name url commit <<<"$line"
  target="$SEED_DIR/$name"
  if [[ -d "$target/.git" ]]; then
    current=$(git -C "$target" rev-parse HEAD)
    if [[ "$current" == "$commit" ]]; then
      echo "[seed] $name already at $commit"
      continue
    fi
    echo "[seed] $name at $current, moving to $commit"
    git -C "$target" fetch --depth 1 origin "$commit"
    git -C "$target" checkout -q "$commit"
  else
    echo "[seed] cloning $name @ $commit"
    git clone --filter=blob:none --no-checkout "$url" "$target"
    git -C "$target" fetch --depth 1 origin "$commit"
    git -C "$target" checkout -q "$commit"
  fi
done < "$LOCK_FILE"

echo "[seed] done"
