# Release notes

The files for one version, named after its `versionName`, written in the same pull request as the version bump, for users rather than developers:

| File | Stable | Preview | What it is |
| --- | --- | --- | --- |
| `0.2.0.md` | required | required | The English notes. They also become the text of the GitHub release. |
| `0.2.0.de.md` | required | optional | German notes. |
| `0.2.0.it.md` | required | optional | Italian notes. |
| `0.2.0.highlights.json` | required | optional | The cards at the top of the update sheet. |

A language that is missing falls back to English in the app. A preview with only the `.md` shows its plain notes, with a "Preview build" banner added automatically.

## Categories

The notes are Markdown: headings, bullets, **bold** and links are rendered. The `##` headings are the categories under "All changes", and there is no fixed list of them: use what fits the release, and put `## Fixes` last. Translate the headings along with the rest.

## Highlight cards

There is no limit on the number of cards; the sheet scrolls, so put the most important first. Keep each `body` to a sentence or two: these are not the bullets of the notes.

```json
{ "highlights": [
  { "icon": "place",
    "title": { "en": "Saved places", "de": "Gespeicherte Orte", "it": "Luoghi salvati" },
    "body":  { "en": "Keep Home, Work and your own places.", "de": "Zuhause, Arbeit und eigene Orte.", "it": "Casa, lavoro e i tuoi luoghi." } }
] }
```

`icon` is a name from [icons.txt](icons.txt). The sheet is drawn by the version already on the phone, so a name added to the app later shows as a generic sparkle until people have updated past that version. To add one: add it to `WhatsNewIcon` and to `WhatsNewIcon.vector` in the app, and to `icons.txt`; a test compares the list with the app.

## Checks

The Release workflow runs `tools/release.py --check-notes` first and stops if a required file is missing or empty, an icon is unknown, or a card lacks text in a required language. Run it yourself before opening the pull request:

```bash
python tools/release.py --check-notes --notes docs/release-notes/<versionName>.md
python tools/release.py --preview --check-notes --notes docs/release-notes/<versionName>.md
```

See "Publishing a release" in the main `README.md`.
