---
name: commit-changes
description: Commit work in this repository, or push to main. Enforces the approval rules - never commit without explicit AskUserQuestion approval, split app/build commits from AI-documentation commits, run the local checks first because there is no CI, never push to main without its own dedicated approval. Use whenever the user asks to commit, save, push, merge or open a pull request, including when they say to do it without asking.
---

# Committing, and pushing to `main`

Both actions are gated on explicit user approval, every single time. "Just commit it" is not approval -
it is the request that starts this procedure.

## 1. Work out what changed, and split it

Run `git status --short` and `git diff --stat`. Sort the changes into two groups:

| Group | Contents |
| --- | --- |
| **App** | `app/`, `core/`, `gradle/`, `*.gradle.kts`, `gradle.properties`, `tools/`, `icons/`, `docs/` outside `docs/ai/`, `README.md`, `.gitignore`/`.gitattributes` lines that are not about `.claude/` |
| **AI material** | `CLAUDE.md`, `docs/ai/`, `.claude/`, the `.claude/` lines in `.gitignore` |

They are separate commits. Never mix them.

**Bugs live in the docs, not in issues** (CLAUDE.md rule 12). A commit that fixes an entry in
`docs/ai/notes/known-defects.md` deletes that entry **in the same commit** - so a bug-fix commit is the one
case where the app change and its `docs/ai/` line travel together. Name the bug in the title in its own
words ("Keep the mobile-data timetable setting across app restarts"). A bug found but not fixed is added
to that note and goes in the AI-material commit.

Only if a GitHub issue already exists for the work (the session started from one, or the user names it):
end the title with `(#<number>)`, and add `Fixes #<number>` to the body when the commit completes it.
Never invent a number, and never create an issue to have one.

## 2. Run the checks - nothing else will

There is no CI ([no CI](../../../docs/ai/notes/no-ci-every-check-is-local.md)). Before asking to commit
an **App** change that touches Kotlin or resources, run, for the modules touched:

```bash
./gradlew :core:model:test :core:data:testDebugUnitTest :app:testDebugUnitTest
./gradlew lintDebug
```

Report which ones you ran and their result in the approval question. If something fails, fix it or say
so plainly - never ask to commit red work without saying it is red. For **AI material**, run
`python .claude/scripts/check_docs_sync.py` and `python .claude/scripts/check_okf.py`.

## 3. Write the message

Match the history (`git log`): the title says what changed **for someone using the app**, several changes
joined by commas - "Stop filters by direction and line, fix search reload loop, enlargeable maps". A
version bump ends the title (`; 0.1.5-preview.1`). The body, when one is needed, is a short bullet list
in the same voice, ending with the version line if there is one: `- Version 0.1.5-preview.1
(versionCode 6)`. A release-only commit is `Release v0.1.4 (versionCode 5)`.

Bad: "changed 12 files, refactored StopViewModel". Good: "Fix: departure searches re-ran on their own
results, so the list kept reloading".

End every message with the attribution trailer the session gives you (`Co-Authored-By: ...`).

## 4. Ask, then commit

Open one `AskUserQuestion` containing **one question per commit** you intend to make, each showing the
exact title (and body, if any) so the user reads the real message before approving. Offer at least:
commit it, skip it for now, or hold everything for a larger commit later.

Only after an approving answer: stage exactly that group's paths (`git add <paths>`, never `git add -A`
when two groups exist) and commit. If the user approves one group and defers the other, commit only the
approved one and leave the rest in the working tree.

If the user declines, say what is left uncommitted and stop. Do not re-ask in the same turn.

## 5. Pushing to `main` - a separate decision

`main` is what every release is cut from, and nothing checks a push to it. So:

- You may work out and show the exact `git push` command you intend to run.
- You must open a **dedicated** `AskUserQuestion` for it, never bundled with a commit question.
- Until an approving answer arrives, do not run the push.

Pushing an ordinary feature branch (anything other than `main`) is not gated by this rule; still say you
are doing it. Publishing a release is its own procedure: the `publish-release` skill.

## 6. After committing

The `PreToolUse` hook in `.claude/settings.json` runs `check_docs_sync.py` before every `git commit` and
blocks the commit when the documentation is internally inconsistent. If it blocks, fix what it names
(usually a stale path or an unindexed note) rather than working around it.

If the code changed in a way the AI material describes (a new data source, a changed realtime rule, a
renamed path, a different release step), update `CLAUDE.md` / `docs/ai/` and take that through step 4 as
the second commit. Tell the user you are doing it; never update the docs silently.
