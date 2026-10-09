"""Validate docs/ai as an Open Knowledge Format bundle.

OKF v0.2 spec: https://github.com/GoogleCloudPlatform/open-knowledge-format

Conformance is deliberately tiny - a bundle is conformant if every non-reserved .md file has parseable
frontmatter carrying a non-empty `type`, and the reserved filenames are used for what they are for.
Those are the only things that fail this script. Everything else it reports is advice: missing
recommended fields, broken links, timestamps without an offset. The spec is explicit that a consumer
MUST NOT reject a bundle for any of those, so neither does this.

Usage (from the repo root):
    python .claude/scripts/check_okf.py
    python .claude/scripts/check_okf.py --strict     # advice becomes failure too

Exit code 1 on a conformance violation. Read-only, standard library only.
"""

import argparse
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BUNDLE = os.path.join(REPO, "docs", "ai")
RESERVED = {"index.md", "log.md"}
RECOMMENDED = ("title", "description", "tags")
KNOWN_TYPES = {"Finding", "Reference", "Decision", "Status", "Convention"}
ISO = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})$")
DATE_HEADING = re.compile(r"^##\s+\d{4}-\d{2}-\d{2}\s*$")
LINK = re.compile(r"\[[^\]]*\]\((?!https?://|mailto:)([^)#]+)(?:#[^)]*)?\)")


def split_frontmatter(text):
    """Return (frontmatter_lines, has_block). Minimal: OKF frontmatter is a leading --- fence."""
    if not text.startswith("---\n"):
        return [], False
    end = text.find("\n---", 3)
    if end == -1:
        return [], False
    return text[4:end].splitlines(), True


def top_level_keys(lines):
    """Map top-level `key: value` pairs. Nested/indented lines belong to the key above them."""
    keys = {}
    current = None
    for line in lines:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line[0] not in " \t-":
            match = re.match(r"^([A-Za-z_][\w-]*)\s*:\s*(.*)$", line)
            if match:
                current = match.group(1)
                keys[current] = match.group(2).strip()
        elif current is not None:
            keys[current] = (keys.get(current) or "") + " " + line.strip()
    return keys


def iter_markdown():
    for root, dirs, files in os.walk(BUNDLE):
        dirs[:] = sorted(dirs)
        for name in sorted(files):
            if name.endswith(".md"):
                path = os.path.join(root, name)
                yield path, os.path.relpath(path, BUNDLE).replace(os.sep, "/"), name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="treat advice as failure")
    args = parser.parse_args()

    if not os.path.isdir(BUNDLE):
        print("No bundle at docs/ai")
        return 1

    errors, advice = [], []
    concepts = 0
    present = {rel for _p, rel, _n in iter_markdown()}

    for path, rel, name in iter_markdown():
        # Text mode reads CRLF checkouts (Windows, core.autocrlf) as LF, so the fence check holds.
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        fm_lines, has_fm = split_frontmatter(text)
        keys = top_level_keys(fm_lines)

        if name in RESERVED:
            # Reserved files are navigation and history, never concept documents.
            if keys.get("type"):
                errors.append("%s: reserved filename must not be a concept document (has `type`)" % rel)
            if rel == "index.md":
                version = keys.get("okf_version", "").strip("\"'")
                if not version:
                    advice.append("index.md: bundle root should declare okf_version")
            elif keys.get("okf_version"):
                errors.append("%s: only the bundle-root index.md may declare okf_version" % rel)
            if name == "log.md":
                headings = [l for l in text.splitlines() if l.startswith("## ")]
                bad = [h for h in headings if not DATE_HEADING.match(h)]
                for h in bad:
                    advice.append("%s: heading is not an ISO date: %s" % (rel, h.strip()))
        else:
            concepts += 1
            if not has_fm:
                errors.append("%s: no YAML frontmatter" % rel)
                continue
            if not keys.get("type"):
                errors.append("%s: frontmatter has no non-empty `type`" % rel)
            elif keys["type"].strip("\"'") not in KNOWN_TYPES:
                advice.append("%s: type '%s' is outside the documented vocabulary" % (rel, keys["type"]))
            for field in RECOMMENDED:
                if field not in keys:
                    advice.append("%s: no `%s`" % (rel, field))
            for family in ("generated", "verified"):
                blob = keys.get(family)
                if blob:
                    # One shape for both, the mapping `generated` uses. A one-item list says the same
                    # thing differently, which is how the two drift apart.
                    if blob.lstrip().startswith("- "):
                        advice.append("%s: %s is a list; use the `by:`/`at:` mapping"
                                      % (rel, family))
                    for stamp in re.findall(r"at:\s*\"?([0-9T:+\-Z]+)\"?", blob):
                        if not ISO.match(stamp):
                            advice.append("%s: %s.at is not ISO 8601 with an offset: %s"
                                          % (rel, family, stamp))

        for target in LINK.findall(text):
            if target.startswith("/"):
                resolved = target.lstrip("/")
            else:
                resolved = os.path.normpath(
                    os.path.join(os.path.dirname(rel), target)).replace(os.sep, "/")
            # Links that leave the bundle (../../README.md) point into the repository, not at a concept.
            if resolved.endswith(".md") and not resolved.startswith("../") and resolved not in present:
                advice.append("%s: link target not in bundle: %s" % (rel, target))

    print("Bundle: docs/ai - %d concept documents, %d files total" % (concepts, len(present)))
    if errors:
        print("\nCONFORMANCE ERRORS (%d):" % len(errors))
        for e in errors:
            print("  " + e)
    if advice:
        print("\nAdvice (%d) - none of this makes the bundle non-conformant:" % len(advice))
        for a in advice:
            print("  " + a)
    if not errors and not advice:
        print("\nConformant, nothing to advise.")
    elif not errors:
        print("\nConformant.")
    return 1 if errors or (advice and args.strict) else 0


if __name__ == "__main__":
    sys.exit(main())
