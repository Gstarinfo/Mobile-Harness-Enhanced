#!/usr/bin/env bash
set -e

# ==============================================================================
# Mobile Harness Enhanced - Upstream Sync Utility
# ==============================================================================
# This script keeps your enhanced fork up-to-date with the upstream repository:
#   Upstream: https://github.com/techjarves/Mobile-Harness
#   Fork:     https://github.com/Gstarinfo/Mobile-Harness-Enhanced
#
# Because your custom features are cleanly modularized in:
#   app/src/main/java/com/jarves/mh/enhanced/
# upstream updates can be rebased and merged smoothly with zero code loss.
# ==============================================================================

UPSTREAM_URL="https://github.com/techjarves/Mobile-Harness.git"
UPSTREAM_BRANCH="main"

echo "=== Mobile Harness Enhanced: Checking Upstream Updates ==="

# 1. Verify clean working tree
if ! git diff-index --quiet HEAD --; then
    echo "Error: Working directory has uncommitted changes. Please commit or stash them first."
    exit 1
fi

# 2. Ensure upstream remote exists
if ! git remote | grep -q "^upstream$"; then
    echo "Adding upstream remote: $UPSTREAM_URL"
    git remote add upstream "$UPSTREAM_URL"
fi

# 3. Fetch latest upstream commits
echo "Fetching updates from upstream..."
git fetch upstream "$UPSTREAM_BRANCH"

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
BEHIND_COUNT=$(git rev-list --count HEAD..upstream/"$UPSTREAM_BRANCH")

if [ "$BEHIND_COUNT" -eq 0 ]; then
    echo "Your fork ($CURRENT_BRANCH) is already completely up to date with upstream!"
    exit 0
fi

echo "Upstream is $BEHIND_COUNT commit(s) ahead. Summary of new updates:"
git log HEAD..upstream/"$UPSTREAM_BRANCH" --oneline -n 10

echo ""
read -p "Apply upstream updates onto $CURRENT_BRANCH using rebase? [y/N] " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Sync cancelled."
    exit 0
fi

echo "Rebasing your custom features on top of latest upstream..."
git rebase upstream/"$UPSTREAM_BRANCH"

echo "Running test suite to verify build stability..."
./gradlew testOnlineDebugUnitTest

echo ""
echo "=== Upstream Sync Completed Successfully! ==="
echo "Your extra features are intact on top of upstream."
echo "To publish the updates to your GitHub fork, run:"
echo "    git push origin $CURRENT_BRANCH --force-with-lease"
