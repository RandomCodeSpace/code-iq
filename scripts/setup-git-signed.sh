#!/usr/bin/env bash
# scripts/setup-git-signed.sh
#
# Apply the repo-local git config required by RAN-46 AC #2.
#
#   user.name        = Amit Kumar
#   user.email       = ak.nitrr13@gmail.com
#   user.signingkey  = ~/.ssh/id_ed25519.pub
#   gpg.format       = ssh
#   commit.gpgsign   = true
#   tag.gpgsign      = true
#
# Idempotent: re-running is a no-op except for the verification block at the end.
# Run from the repo root (or any subdirectory of the worktree).

set -euo pipefail

# Resolve the worktree root and refuse to run anywhere else.
if ! repo_root=$(git rev-parse --show-toplevel 2>/dev/null); then
  echo "error: not inside a git working tree." >&2
  exit 1
fi

cd "$repo_root"

# Defaults can be overridden by env vars so the same script works for forks.
GIT_USER_NAME=${GIT_USER_NAME:-"Amit Kumar"}
GIT_USER_EMAIL=${GIT_USER_EMAIL:-"ak.nitrr13@gmail.com"}
GIT_SIGNING_KEY=${GIT_SIGNING_KEY:-"$HOME/.ssh/id_ed25519.pub"}

# The signing key path must exist before we wire up signing — otherwise every
# commit will fail and the local config will be silently broken.
if [ ! -f "$GIT_SIGNING_KEY" ]; then
  cat >&2 <<EOF
error: SSH signing key not found at $GIT_SIGNING_KEY

Generate one with:
    ssh-keygen -t ed25519 -C "$GIT_USER_EMAIL"
Then upload the public key (\$GIT_SIGNING_KEY) to your GitHub account under:
    Settings → SSH and GPG keys → New SSH key → Key type: Signing Key
And re-run this script.
EOF
  exit 2
fi

apply() {
  local key="$1" value="$2"
  git config --local "$key" "$value"
}

apply user.name        "$GIT_USER_NAME"
apply user.email       "$GIT_USER_EMAIL"
apply user.signingkey  "$GIT_SIGNING_KEY"
apply gpg.format       ssh
apply commit.gpgsign   true
apply tag.gpgsign      true

echo "Applied repo-local git config:"
printf "  %-22s = %s\n" \
  user.name        "$(git config --local --get user.name)" \
  user.email       "$(git config --local --get user.email)" \
  user.signingkey  "$(git config --local --get user.signingkey)" \
  gpg.format       "$(git config --local --get gpg.format)" \
  commit.gpgsign   "$(git config --local --get commit.gpgsign)" \
  tag.gpgsign      "$(git config --local --get tag.gpgsign)"

# Verification: produce a throwaway signed object and verify it.
# `git commit-tree` does not touch refs, so this is non-destructive.
echo
echo "Verifying signing produces a valid signature ..."
tree=$(git write-tree)
sig_commit=$(echo "setup-git-signed.sh verification" | git commit-tree "$tree" -S)
if git verify-commit --raw "$sig_commit" 2>&1 | grep -q '^GOODSIG\|^SSH_OK\|GOOD signature'; then
  echo "  ok — signing chain is healthy."
elif git verify-commit "$sig_commit" >/dev/null 2>&1; then
  echo "  ok — signing chain is healthy."
else
  cat >&2 <<EOF
warning: signing config applied but verification failed.

  Most common cause: the corresponding allowed-signers file is missing.
  Configure one with:
    git config --local gpg.ssh.allowedSignersFile ~/.config/git/allowed_signers
    printf "$GIT_USER_EMAIL %s\n" "\$(cat $GIT_SIGNING_KEY)" \\
      >> ~/.config/git/allowed_signers
  Then re-run this script.
EOF
  exit 3
fi

echo
echo "done. Every commit and tag from this worktree will now be ssh-signed."
