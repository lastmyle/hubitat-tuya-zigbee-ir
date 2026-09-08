---
name: worktree
description: >-
  Move this session into its own git worktree under .claude/worktrees/, branched
  off the integration branch. Use when the collision-check hook reports
  uncommitted changes, another worktree or another running session; when the
  owner says "start a worktree" or "work in a worktree"; or before editing files
  in a checkout whose changes this session did not make.
---

# Work in a worktree

Several Claude sessions run against these repos at once. A session that edits a
checkout holding someone else's uncommitted work destroys it. Move to a worktree
instead.

## When to move

Move before the first edit if any of these is true:

- the `collision-check` SessionStart hook reported uncommitted changes, more
  than one worktree, or more than one running Claude session
- `git status --porcelain` shows changes this session did not make
- the owner asked for a worktree

Stay in the main checkout only when the owner says the changes are theirs to
continue, or when the tree is clean and no other session is running.

## How to move

1. Pick the base branch: `origin/development` if the repo has one, otherwise
   `origin/main`. Check with
   `git show-ref --verify --quiet refs/remotes/origin/development`.
2. Fetch, then create the worktree with a name that says what the work is:

   ```bash
   git fetch origin
   git worktree add .claude/worktrees/<name> -b <type>/<subject> origin/development
   ```

   Branch names follow the repo's git conventions (`feat/`, `fix/`, `chore/`).
3. Enter it: call the `EnterWorktree` tool with `path` set to the new directory.
   This switches the session's working directory, so later tool calls run in the
   worktree.
4. Install what the worktree needs to build (`npm ci`, `uv sync`, `mvn`), since
   a new worktree has no `node_modules` or `.venv`.
5. Copy any git-ignored config the app needs to run, such as `.env`. It does not
   come across with the worktree.

`EnterWorktree` on its own (with `name` instead of `path`) branches from the
repo's default branch, which is `main` on most of these repos. Use the two-step
above wherever work belongs on `development`.

## While you are on a worktree

- Say which worktree and branch you are on at the top of every reply, for
  example: On worktree `.claude/worktrees/address-search` (branch
  `fix/address-search`).
- Never commit, revert or stash the main checkout's changes.
- Push and raise the PR from the worktree branch as normal.

## Cleaning up

Once the branch is merged:

```bash
git worktree remove .claude/worktrees/<name>
git worktree prune
```

Ask the owner before removing a worktree this session did not create.
