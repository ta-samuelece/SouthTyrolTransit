"""Report whether the AI material has fallen behind the repository.

This repo is set up so agents can work on it, which only holds while CLAUDE.md and docs/ai/ describe
reality. Code changes by hand, in chat, and through pulls from other people; this script is how a
session finds out what has happened since the knowledge was last touched.

It reports four things:

  1. Structural commits    - changes to the paths the documentation actually describes. These are the
                             ones that can make the docs wrong. Reported, never fatal (see below).
  2. Other commits         - feature and screen churn. Rarely invalidates the docs, but shown so nothing
                             is invisible.
  3. Uncommitted work      - the same split, for the working tree.
  4. OKF spec baseline     - when the Open Knowledge Format spec was last reviewed against the bundle.

And it checks the documentation against itself, which is what fails the run:

  - a top-level heading that appears twice in one document (content appended instead of replaced);
  - a backticked repository path that does not exist, including anything under a deleted top level
    listed in REMOVED_ROOTS (gitignored paths are skipped - they are personal or build output - and so
    are elided paths containing "...");
  - a markdown link to a file that is not there;
  - a note in docs/ai/notes/ or a record in docs/ai/decisions/ missing from its index;
  - docs/ai/status.md over its line budget.

Usage (from the repo root):
    python .claude/scripts/check_docs_sync.py
    python .claude/scripts/check_docs_sync.py --files      # also list the paths touched
    python .claude/scripts/check_docs_sync.py --since REV  # compare against a revision
    python .claude/scripts/check_docs_sync.py --hook       # JSON for the SessionStart hook; on a fresh
                                                           # session it also lists the open defects and
                                                           # asks the agent to raise them (rule 12)

Exit code 1 when the documentation is internally inconsistent. Structural drift alone never fails: CLAUDE.md
rule 3 puts the doc update in its own commit after the code commit, so failing on drift would deadlock that
sequence. A PreToolUse hook in .claude/settings.json runs this before every `git commit` and blocks the
commit on a non-zero exit. Read-only, standard library only.
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# The AI-facing material whose last change is the baseline.
DOC_PATHS = ["CLAUDE.md", "docs/ai", ".claude"]

# Paths the documentation makes claims about. A change here means the docs may now be wrong.
# Feature screens under app/src/main/kotlin/.../feature/ and resources are deliberately absent: they
# churn with every release and the docs describe the *shape* (identifiers, time, the databases, parsers,
# realtime merge, navigation, release flow), not each screen. Such changes are still reported as
# "other commits"; nothing here is ever fatal.
STRUCTURAL = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    "app/build.gradle.kts",
    "core/model/build.gradle.kts",
    "core/data/build.gradle.kts",
    "core/designsystem/build.gradle.kts",
    "core/map/build.gradle.kts",
    "core/model/src/main/kotlin/org/southtyrol/transit/model/Approach.kt",
    "core/model/src/main/kotlin/org/southtyrol/transit/model/Interfaces.kt",
    "core/model/src/main/kotlin/org/southtyrol/transit/model/Progress.kt",
    "core/designsystem/src/main/kotlin/org/southtyrol/transit/design/Tokens.kt",
    "core/model/src/main/kotlin/org/southtyrol/transit/model/Realtime.kt",
    "core/model/src/main/kotlin/org/southtyrol/transit/model/Text.kt",
    "core/model/src/main/kotlin/org/southtyrol/transit/model/Time.kt",
    "core/model/src/main/kotlin/org/southtyrol/transit/model/Transit.kt",
    "core/data/src/main/kotlin/org/southtyrol/transit/data/TransitRepository.kt",
    "core/data/src/main/kotlin/org/southtyrol/transit/data/Database.kt",
    "core/data/src/main/kotlin/org/southtyrol/transit/data/Efa.kt",
    "core/data/src/main/kotlin/org/southtyrol/transit/data/GtfsImporter.kt",
    "core/data/src/main/kotlin/org/southtyrol/transit/data/Network.kt",
    "core/data/src/main/kotlin/org/southtyrol/transit/data/Realtime.kt",
    "core/data/src/main/kotlin/org/southtyrol/transit/data/ScheduleStore.kt",
    "core/data/consumer-rules.pro",
    "core/map/src/main/kotlin/org/southtyrol/transit/map",
    "app/proguard-rules.pro",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/kotlin/org/southtyrol/transit/TransitApplication.kt",
    "app/src/main/kotlin/org/southtyrol/transit/di",
    "app/src/main/kotlin/org/southtyrol/transit/ui",
    "app/src/main/kotlin/org/southtyrol/transit/update",
    "app/src/main/kotlin/org/southtyrol/transit/work",
    "app/src/test",
    "tools",
    "README.md",
    "docs/API_DISCOVERY.md",
    ".gitignore",
    ".gitattributes",
    ".github",
]

# How long an OKF specification review stays fresh.
SPEC_REVIEW_DAYS = 90
SPEC_DOC = "docs/ai/okf-conformance.md"
SPEC_URL = "https://github.com/GoogleCloudPlatform/open-knowledge-format"

# Documents whose length is the point. A handover that grows into an archive is how drift starts:
# findings belong in notes/, history in git and log.md, bugs in notes/known-defects.md.
SIZE_BUDGET = {"docs/ai/status.md": 60}

# Paths a document may name only as history. Anything else that looks like a repository path is
# checked for existence.
HISTORY_ONLY = ("docs/ai/log.md", "docs/ai/decisions")

# Top-level directories that were deleted. Add to this when you remove one: it is what makes the check
# flag every document still pointing at it.
REMOVED_ROOTS = ()

# The defect register. At session start the hook lists its open entries and tells the agent to ask the
# user whether they should move to GitHub issues (CLAUDE.md rule 12). Entries are numbered bold lines.
DEFECTS_DOC = "docs/ai/notes/known-defects.md"
DEFECT_ENTRY = re.compile(r"^\d+\.\s+\*\*(.+?)\*\*", re.M)

# Indexed directories: every concept document in them must be listed in their index.md.
INDEXED = ("docs/ai/notes", "docs/ai/decisions")

SOURCE_SUFFIXES = (".md", ".py", ".sh", ".kt", ".kts", ".toml", ".properties", ".xml", ".pro", ".json",
                   ".yml", ".yaml", ".txt", ".pb", ".bin", ".png", ".jar", ".apk", ".bat")

PATH_PATTERN = re.compile(r"`([A-Za-z0-9_.-]+/[A-Za-z0-9_./+-]*[A-Za-z0-9_/-])`")


def git(*args):
    result = subprocess.run(
        ["git"] + list(args), cwd=REPO, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    return result.stdout.strip() if result.returncode == 0 else ""


def lines(text):
    return [l for l in text.splitlines() if l.strip()]


def spec_baseline():
    """Return (version, last_review_date, age_days) from the conformance document."""
    path = os.path.join(REPO, SPEC_DOC)
    if not os.path.exists(path):
        return None, None, None
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    stamps = re.findall(r"at:\s*\"?(\d{4}-\d{2}-\d{2})", text.split("\n---", 2)[0])
    version = None
    index = os.path.join(REPO, "docs", "ai", "index.md")
    if os.path.exists(index):
        with open(index, encoding="utf-8") as handle:
            match = re.search(r"okf_version:\s*\"?([\d.]+)", handle.read())
            if match:
                version = match.group(1)
    if not stamps:
        return version, None, None
    last = max(stamps)
    age = (datetime.date.today() - datetime.date.fromisoformat(last)).days
    return version, last, age


def doc_files():
    """Every markdown document the rules govern."""
    for base in ("CLAUDE.md", "README.md"):
        f = os.path.join(REPO, base)
        if os.path.exists(f):
            yield base, f
    for top in (os.path.join("docs", "ai"), os.path.join(".claude", "skills")):
        for root, _dirs, names in os.walk(os.path.join(REPO, top)):
            for name in sorted(names):
                if name.endswith(".md"):
                    f = os.path.join(root, name)
                    yield os.path.relpath(f, REPO).replace(os.sep, "/"), f


def ignored(path):
    """True when git ignores the path - personal files such as settings.local.json, or build output."""
    result = subprocess.run(["git", "check-ignore", "-q", path], cwd=REPO, capture_output=True)
    return result.returncode == 0


def check_rot():
    """Internal consistency: duplicate headings, dead paths, broken links, indexes, size."""
    problems = []

    for rel, full in doc_files():
        try:
            with open(full, encoding="utf-8") as handle:
                text = handle.read()
        except OSError:
            continue

        # a heading twice in one document means content was appended, not replaced
        headings = [l.strip() for l in text.splitlines() if l.startswith("# ")]
        for h in sorted({h for h in headings if headings.count(h) > 1}):
            problems.append("%s: heading appears %d times: %s" % (rel, headings.count(h), h))

        budget = SIZE_BUDGET.get(rel)
        if budget:
            counted = len(text.splitlines())
            if counted > budget:
                problems.append("%s: %d lines, budget %d - move findings into notes/, bugs into "
                                "notes/known-defects.md" % (rel, counted, budget))

        # markdown links to files inside the repository
        for target in re.findall(r"\]\(([^)#]+\.md)\)", text):
            if target.startswith(("http:", "https:")):
                continue
            if not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(full), target))):
                problems.append("%s: link does not resolve: %s" % (rel, target))

        if rel.startswith(HISTORY_ONLY):
            continue

        # Backticked repository paths that no longer exist. Only things that are unambiguously a path
        # are judged - a known file extension or a trailing slash, rooted in a real top-level directory.
        for candidate in sorted(set(PATH_PATTERN.findall(text))):
            if candidate.startswith(("./", "../", "http")):
                continue
            if "..." in candidate:
                continue  # an elided path (core/model/.../Interfaces.kt) is shorthand, not a claim
            if not (candidate.endswith("/") or candidate.endswith(SOURCE_SUFFIXES)):
                continue
            head = candidate.split("/", 1)[0]
            if not (os.path.isdir(os.path.join(REPO, head)) or head in REMOVED_ROOTS):
                continue  # a path on a device, in a resource set, or in another repository
            if os.path.exists(os.path.join(REPO, candidate.rstrip("/"))) or ignored(candidate):
                continue
            problems.append("%s: names a path that is not there: %s" % (rel, candidate))

    # every concept document in an indexed directory is listed in its index
    for directory in INDEXED:
        folder = os.path.join(REPO, *directory.split("/"))
        index = os.path.join(folder, "index.md")
        if not os.path.exists(index):
            continue
        with open(index, encoding="utf-8") as handle:
            listed = handle.read()
        for name in sorted(os.listdir(folder)):
            if name.endswith(".md") and name not in ("index.md", "log.md") and name not in listed:
                problems.append("%s/index.md: does not list %s" % (directory, name))

    return problems


def collect(baseline, paths):
    log = git("log", "--oneline", "%s..HEAD" % baseline, "--", *paths)
    return lines(log)


def report(args):
    out = []
    failed = False

    if args.since:
        baseline, label = args.since, "since %s" % args.since
        out.append("Comparing against %s" % args.since)
    else:
        baseline = git("log", "-1", "--format=%H", "--", *DOC_PATHS)
        label = "since the docs were last updated"
        if baseline:
            when = git("log", "-1", "--format=%ad", "--date=short", baseline)
            subject = git("log", "-1", "--format=%s", baseline)
            out.append("AI material last updated in %s (%s): %s" % (baseline[:7], when, subject))
        else:
            out.append("No commit has touched the AI material yet - nothing to compare against.")

    structural, other = [], []
    if baseline:
        structural = collect(baseline, STRUCTURAL)
        everything = collect(baseline, ["."])
        doc_only = set(collect(baseline, DOC_PATHS))
        other = [c for c in everything if c not in set(structural) and c not in doc_only]

    if structural:
        # Reported, never fatal: the doc update is its own commit after the code commit (rule 3), so
        # failing here would deadlock that sequence. Only internal rot blocks a commit.
        out.append("")
        out.append("%d commit(s) changed documented structure %s:" % (len(structural), label))
        out.extend("  " + c for c in structural)
    if other:
        out.append("")
        out.append("%d other commit(s) %s (informational):" % (len(other), label))
        out.extend("  " + c for c in other[:10])
        if len(other) > 10:
            out.append("  ... and %d more" % (len(other) - 10))

    dirty = lines(git("status", "--porcelain"))
    if dirty:
        touched = [d[3:].strip().strip('"') for d in dirty]
        struct_dirty = [p for p in touched if any(p == s or p.startswith(s + "/") for s in STRUCTURAL)]
        out.append("")
        out.append("Working tree: %d uncommitted path(s)%s" % (
            len(touched),
            ", %d of them documented structure" % len(struct_dirty) if struct_dirty else "",
        ))
        for p in struct_dirty[:10]:
            out.append("  " + p)

    if args.files and baseline and (structural or other):
        touched = lines(git("diff", "--name-only", "%s..HEAD" % baseline))
        out.append("")
        out.append("Paths touched (%d):" % len(touched))
        out.extend("  " + p for p in sorted(set(touched)))

    version, last, age = spec_baseline()
    out.append("")
    if last is None:
        out.append("OKF baseline: no review recorded in %s" % SPEC_DOC)
    else:
        stale = age is not None and age > SPEC_REVIEW_DAYS
        out.append("OKF baseline: v%s, spec last reviewed %s (%d days ago)%s"
                   % (version or "?", last, age, " - REVIEW DUE" if stale else ""))
        if stale:
            out.append("  Re-read %s and propose what a newer version would change." % SPEC_URL)

    rot = check_rot()
    if rot:
        failed = True
        out.append("")
        out.append("Documentation is internally inconsistent - fix before committing:")
        out.extend("  " + problem for problem in rot)

    if structural:
        out.append("")
        out.append("Review whether CLAUDE.md and docs/ai/ still describe reality. Update what drifted,")
        out.append("tell the user what you changed, and commit the doc update separately.")

    return out, failed


def open_defects():
    """Titles of the numbered entries in the known-defects register."""
    path = os.path.join(REPO, *DEFECTS_DOC.split("/"))
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as handle:
        return DEFECT_ENTRY.findall(handle.read())


def defects_prompt(source):
    """The session-start instruction to ask about the open defects (CLAUDE.md rule 12).

    Only on a fresh session or after /clear - not on resume or after compaction, where the question was
    already asked earlier in the same conversation."""
    if source not in ("startup", "clear"):
        return []
    titles = open_defects()
    if not titles:
        return []
    out = ["", "Open defects in %s (%d):" % (DEFECTS_DOC, len(titles))]
    out.extend("  %d. %s" % (i, t) for i, t in enumerate(titles, 1))
    out.append("ACTION (CLAUDE.md rule 12): before starting the user's first task, ask them in one "
               "AskUserQuestion whether these defects should move to GitHub issues on the main "
               "repository or keep being tracked in %s. Offer: keep tracking in the file; move all "
               "to issues; move selected ones to issues. Then answer their request." % DEFECTS_DOC)
    return out


def hook_source():
    """The SessionStart `source` (startup, resume, clear, compact) from the hook's stdin JSON."""
    try:
        if sys.stdin is None or sys.stdin.isatty():
            return "startup"
        data = sys.stdin.read()
        return json.loads(data).get("source", "startup") if data.strip() else "startup"
    except Exception:
        return "startup"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--files", action="store_true", help="list the paths touched")
    parser.add_argument("--since", metavar="REV", help="compare against this revision")
    parser.add_argument("--hook", action="store_true", help="emit SessionStart hook JSON")
    args = parser.parse_args()

    if args.hook:
        # Never let a hook break a session: on any failure, say nothing.
        try:
            out, _failed = report(args)
            out += defects_prompt(hook_source())
            payload = {
                "hookSpecificOutput": {
                    "hookEventName": "SessionStart",
                    "additionalContext":
                        "Repository/documentation sync status (CLAUDE.md rule 6):\n" + "\n".join(out),
                },
                "suppressOutput": True,
            }
        except Exception:
            payload = {"suppressOutput": True}
        print(json.dumps(payload))
        return 0

    out, failed = report(args)
    print("\n".join(out))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
