---
okf_version: "0.2"
---

# AI knowledge base

Shared, committed context so every clone, every teammate and every new session starts from the same
accumulated knowledge. `/CLAUDE.md` is loaded automatically at the start of a session and points here.

This directory is an [Open Knowledge Format](https://github.com/GoogleCloudPlatform/open-knowledge-format)
v0.2 bundle: plain markdown with YAML frontmatter, readable by hand and machine-queryable by agents. It is
deliberately separate from `README.md`, `docs/API_DISCOVERY.md`, `docs/FEATURE_PARITY.md` and
`docs/PUSH_BACKEND.md` - those are the human documentation this project already had, and they stay
authoritative for what they cover. This bundle holds what an agent (or a returning session) would
otherwise have to re-investigate.

# Start here

* [Conventions](./conventions.md) - what belongs in this bundle, how to write an entry, the `type` vocabulary.
* [In-flight work](./status.md) - handover note: what is happening right now on the working branch. Usually empty.
* [Change log](./log.md) - dated history of this bundle's structure and conventions.
* [OKF conformance](./okf-conformance.md) - the format version this bundle targets and what to do when it moves.

# Knowledge

* [Notes](./notes/index.md) - findings that cost time to learn, and standing facts about the repository.
* [Decisions](./decisions/index.md) - one record per non-obvious decision, with its consequences.
