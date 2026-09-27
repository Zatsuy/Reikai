#!/usr/bin/env bash
# One-time GitHub setup for Reikai JP. The owner runs it, never an agent: it creates the key every
# app release is signed with. Safe to run again: it never replaces an existing signing key.
#
#   scripts/fork/setup-github.sh
#
# It sets up two things on Zatsuy/Reikai:
#  1. A deploy key the automatic upstream sync pushes with. GitHub's own Actions token cannot push
#     commits that change workflow files, and upstream's history sometimes does. The script then
#     proves the key can push such a commit, on a throwaway branch it deletes again.
#  2. The release signing key, stored as GitHub secrets, with a backup in
#     ~/.local/share/reikai-jp/signing/. Android only installs an update signed with the same key
#     as the installed app, so this key is made once and kept for good.
# Nothing secret is printed. Needs: gh (logged in), git, ssh-keygen, keytool (from the JDK), openssl.
set -euo pipefail

REPO="Zatsuy/Reikai"
BACKUP_DIR="$HOME/.local/share/reikai-jp/signing"
ALIAS="reikai-jp"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

say() { printf '\n== %s\n' "$*"; }
fail() { printf '\nStopped: %s\n' "$*" >&2; exit 1; }

command -v gh >/dev/null || fail "the GitHub CLI is missing. Run: sudo dnf install -y gh"
gh auth status >/dev/null 2>&1 || fail "the GitHub CLI is not logged in. Run: gh auth login --web --git-protocol ssh"
for tool in git ssh-keygen keytool openssl base64; do
    command -v "$tool" >/dev/null || fail "$tool is missing."
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
secrets="$(gh api "repos/$REPO/actions/secrets" --jq '.secrets[].name')"

say "1/2 Deploy key for the automatic upstream sync"
title="Reikai JP upstream sync (GitHub Actions)"
old_ids="$(gh api "repos/$REPO/keys" --jq ".[] | select(.title == \"$title\") | .id")"
ssh-keygen -q -t ed25519 -N "" -C "reikai-jp-upstream-sync" -f "$work/sync_key"
gh api "repos/$REPO/keys" -f title="$title" -f key="$(cat "$work/sync_key.pub")" -F read_only=false >/dev/null
gh secret set SYNC_DEPLOY_KEY --repo "$REPO" < "$work/sync_key"
for id in $old_ids; do
    gh api -X DELETE "repos/$REPO/keys/$id" >/dev/null
done
echo "Deploy key added, stored as the SYNC_DEPLOY_KEY secret (any older one removed)."

echo "Checking it can push a commit that changes a workflow file (throwaway branch)..."
git -C "$ROOT" fetch -q origin main
base="$(git -C "$ROOT" rev-parse origin/main)"
export GIT_INDEX_FILE="$work/index"
git -C "$ROOT" read-tree "$base"
blob="$( { git -C "$ROOT" show "$base:.github/workflows/fork-ci.yml"; echo "# deploy key probe"; } \
    | git -C "$ROOT" hash-object -w --stdin)"
git -C "$ROOT" update-index --cacheinfo "100644,$blob,.github/workflows/fork-ci.yml"
tree="$(git -C "$ROOT" write-tree)"
unset GIT_INDEX_FILE
commit="$(git -C "$ROOT" commit-tree "$tree" -p "$base" -m "chore(ci): deploy key probe")"
probe="refs/heads/probe/sync-deploy-key"
export GIT_SSH_COMMAND="ssh -i $work/sync_key -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
if git -C "$ROOT" push -q "git@github.com:$REPO.git" "$commit:$probe" 2>"$work/push.err"; then
    git -C "$ROOT" push -q "git@github.com:$REPO.git" ":$probe" 2>/dev/null || true
    echo "OK: the sync can push upstream's workflow changes."
else
    sed 's/^/    /' "$work/push.err"
    echo "WARNING: the deploy key could not push a workflow change. Tell the agent; the sync still"
    echo "works until upstream edits one of its workflow files."
fi
unset GIT_SSH_COMMAND

say "2/2 Release signing key"
if grep -qx SIGNING_KEY <<<"$secrets"; then
    echo "Already set up (the SIGNING_KEY secret exists), so it is left alone: a new key would stop"
    echo "your installed app from accepting updates."
else
    keystore="$BACKUP_DIR/reikai-jp-release.jks"
    props="$BACKUP_DIR/keystore.properties"
    if [[ -f "$keystore" && -f "$props" ]]; then
        echo "Reusing the key backed up in $BACKUP_DIR."
        KS_PASS="$(sed -n 's/^storePassword=//p' "$props")"
    else
        mkdir -p "$BACKUP_DIR"
        chmod 700 "$BACKUP_DIR"
        KS_PASS="$(openssl rand -hex 24)"
        export KS_PASS
        keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias "$ALIAS" \
            -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Reikai JP" \
            -storepass:env KS_PASS -keypass:env KS_PASS >/dev/null 2>&1
        printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
            "$keystore" "$KS_PASS" "$ALIAS" "$KS_PASS" > "$props"
        chmod 600 "$keystore" "$props"
    fi
    base64 -w0 "$keystore" | gh secret set SIGNING_KEY --repo "$REPO"
    printf '%s' "$KS_PASS" | gh secret set KEY_STORE_PASSWORD --repo "$REPO"
    printf '%s' "$KS_PASS" | gh secret set KEY_PASSWORD --repo "$REPO"
    printf '%s' "$ALIAS" | gh secret set ALIAS --repo "$REPO"
    unset KS_PASS
    echo "Signing key created and stored as GitHub secrets. Backup: $BACKUP_DIR"
    echo "Optional: copy that folder somewhere safe (a USB stick, your cloud drive). GitHub keeps"
    echo "its own copy, so you only need the backup if the GitHub repository is ever deleted."
fi

say "Done. Tell the agent it worked."
