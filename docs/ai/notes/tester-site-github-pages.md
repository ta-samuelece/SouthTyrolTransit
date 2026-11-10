---
type: Reference
title: The tester site on GitHub Pages
description: Since 2026-11-10 a static site built by tools/build_site.py from site/ is the Google Play closed-test sign-up page and the public privacy page; English, German and Italian pages come from one template and three string files with identical keys; the privacy page is rendered from docs/PRIVACY.md; the Pages workflow self-hosts the fonts and needs Pages set to "GitHub Actions" once; the icon font is subset to site.json's list.
tags: [site, github-pages, play-store, privacy, closed-test]
generated:
  by: claude-code/claude-opus-5-5
  at: "2026-11-10T23:30:00Z"
verified:
  by: claude-code/claude-opus-5-5
  at: "2026-11-10T23:30:00Z"
status: stable
---

Google Play asks a new personal developer account for 12 testers opted in to a closed test for 14 days
in a row. The closed test's tester list is the Google Group `south-tyrol-transit`; the site explains the
three steps (join the group, opt in on `play.google.com/apps/testing/<package>`, install) and is also the
privacy policy's public address. Human documentation: `docs/DEVELOPMENT.md`, section "Tester site".

# How it is built

`tools/build_site.py` fills `site/template.html` once per language in `site/site.json` (`en` at the root,
the others under `/<lang>/`) from `site/strings/<lang>.json`, and fills `site/privacy.html` with
`docs/PRIVACY.md` rendered by the `markdown` package (3.7, tables and toc, so `## English` gets the
`#english` anchor the policy's own language links use). Placeholders are `{{key}}`; a key ending in
`_html` is inserted unescaped and only the script sets such keys. The build exits on a placeholder without
a value and on a language whose key set differs from English - the site's equivalent of
`TranslationsTest`. Screenshots are copied from `docs/screenshots/` by the names in `site.json`; the Play
links come from its `package`, so a package rename (see `docs/DEVELOPMENT.md`) must update it.

Output goes to `_site/` (gitignored) with a `.nojekyll`, because GitHub Pages would otherwise run Jekyll.

# Publishing

`.github/workflows/pages.yml` runs on pushes to `main` that touch `site/`, `tools/build_site.py`,
`docs/PRIVACY.md`, `docs/screenshots/` or itself, and by hand. It needs the repository's Pages source set
to "GitHub Actions" once (the owner's step) and deploys through the `github-pages` environment. It builds
with `--self-host-fonts`: the Roboto CSS (Latin subsets only) and the Material Symbols Rounded CSS are
fetched with a browser user agent (Google serves woff2 only to browsers it recognises), the font files are
saved under `fonts/` and `fonts.css` replaces the Google Fonts links, so visitors never contact Google -
loading Google Fonts from the page would send their IP addresses to Google. The icon font is requested
with `icon_names=` from `site.json`'s `icons`, so an icon added to the template but not to that list
renders as its name in letters.

# Behaviour worth knowing

- The English page sends a first-time visitor whose browser language is German or Italian to `/de/` or
  `/it/`; picking a language in the switch is remembered in `localStorage` and stops that.
- The page follows the system light or dark theme through `prefers-color-scheme`; the heading keeps the
  feature graphic's own colours (`#004b49` sky, `#79e3d1` hill) in both.
- "Send feedback" points at the store listing: testers send Play's private feedback there, which only the
  developer sees in Play Console.

# What was checked

On 2026-11-10, on Windows with Python 3.11: the build with and without `--self-host-fonts` (three font
files, the icon subset 6.6 KB); the English, German and privacy pages in a browser at desktop width and at
375 px, in light and dark, with no horizontal scroll and no console errors. Not checked: the Pages
workflow on GitHub (it has not run yet), the language redirect with a German or Italian browser, and the
Play links, which work only once the closed test is live.
