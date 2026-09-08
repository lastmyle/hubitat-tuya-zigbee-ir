#!/bin/bash
# Surface cross-session collisions at the start of every session, and send the
# session to a worktree when the checkout already holds work it does not own.
#
# Why this exists: several Claude sessions and git worktrees often run against
# these repos at once (see CLAUDE.md, "Working alongside other sessions"). This
# runs the checks so a session always *sees* the collision, instead of relying
# on the model remembering to look.
#
# SessionStart hook: stdout is added to the session context, so keep it short.
# No `set -e`: a probe that fails must not abort the report.
set -uo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
command -v git >/dev/null 2>&1 || exit 0
git rev-parse --git-dir >/dev/null 2>&1 || exit 0

branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)

# Already on a linked worktree: there is nothing to move to, so just pin down
# where this session is. A linked worktree has a .git file pointing elsewhere,
# so its git-dir and git-common-dir differ; in the main checkout they match.
if [ "$(git rev-parse --git-dir 2>/dev/null)" != "$(git rev-parse --git-common-dir 2>/dev/null)" ]; then
  echo "On worktree $(pwd) (branch ${branch:-unknown})."
  echo "Say so at the top of every reply, so the owner does not mistake it for the main checkout."
  exit 0
fi

# The branch a new worktree should start from.
base=""
for ref in origin/development origin/main origin/master; do
  if git show-ref --verify --quiet "refs/remotes/$ref"; then base="$ref"; break; fi
done
base="${base:-HEAD}"

# Other worktrees on this repo.
worktrees=$(git worktree list 2>/dev/null)
wt_count=$(printf '%s\n' "$worktrees" | grep -c .)

# Uncommitted work already in this checkout. Ignore the three paths a session
# churns on its own — a dependency install or Claude Code writing its local
# settings is not another session's work.
dirty=$(git status --porcelain 2>/dev/null \
  | grep -vE '(package-lock\.json|\.claude/settings\.local\.json|\.claude/worktrees/)$')

# Other running Claude CLI sessions. The interactive CLI line carries
# --output-format; the [c]laude bracket keeps this grep from matching itself.
others=$(ps -eo args 2>/dev/null | grep -c '[c]laude .*--output-format')
[ "$others" -lt 1 ] && others=1   # at minimum, this session

if [ "$wt_count" -gt 1 ] || [ -n "$dirty" ] || [ "$others" -gt 1 ]; then
  echo "⚠️  Cross-session collision check (CLAUDE.md, \"Working alongside other sessions\")."
  if [ "$wt_count" -gt 1 ]; then
    echo ""
    echo "  Worktrees ($wt_count):"
    printf '%s\n' "$worktrees" | sed 's/^/    /'
  fi
  if [ "$others" -gt 1 ]; then
    echo ""
    echo "  $others Claude CLI sessions look to be running against this repo."
  fi
  if [ -n "$dirty" ]; then
    echo ""
    echo "  Uncommitted changes are already in this checkout (branch ${branch:-unknown}):"
    printf '%s\n' "$dirty" | sed 's/^/    /'
    echo ""
    echo "  They may belong to another session or to the owner. Do NOT commit,"
    echo "  revert or stash them. Before you edit any file, move this session to"
    echo "  its own worktree: call the EnterWorktree tool, or run"
    echo "    git worktree add .claude/worktrees/<name> -b <branch> $base"
    echo "  Then say which worktree you are on at the top of every reply."
    echo ""
    echo "  Only stay in this checkout if the owner tells you the changes are"
    echo "  yours to continue."
  fi
else
  echo "Session start: no cross-session collision (one worktree, clean tree)."
fi
exit 0
