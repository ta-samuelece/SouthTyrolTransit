"""Builds the tester sign-up site published on GitHub Pages.

    python tools/build_site.py                      # writes _site/, fonts loaded from Google Fonts
    python tools/build_site.py --self-host-fonts    # what the Pages workflow runs: fonts copied into the site

Sources, each kept in one place:

  * site/template.html and site/strings/<lang>.json - the sign-up page; English at /, the others at /<lang>/
  * docs/PRIVACY.md - rendered into /privacy/ through site/privacy.html (needs the `markdown` package)
  * docs/screenshots/ - the screenshots named in site/site.json
  * site/assets/, site/style.css - copied as they are

The build fails on a placeholder with no text, or on a language whose keys differ from English, so a
string added to one language cannot be forgotten in the others.

With --self-host-fonts the Roboto and Material Symbols files are downloaded at build time and served
from the site itself, so visitors' browsers never contact Google Fonts.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import shutil
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
PLACEHOLDER = re.compile(r"\{\{(\w+)\}\}")
ROBOTO_CSS = "https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;600;700&display=swap"
SYMBOLS_CSS = ("https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@20..48,400,1,0"
               "&icon_names={icons}&display=block")
# Google Fonts serves woff2 only to browsers it recognises.
BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
FONT_SUBSETS = {"latin", "latin-ext"}


def fill(template: str, values: dict[str, str], name: str) -> str:
    """Replaces every {{key}}; keys ending in _html are inserted as they are, the rest are escaped."""
    def sub(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in values:
            sys.exit(f"{name}: no value for {{{{{key}}}}}")
        value = values[key]
        return value if key.endswith("_html") else html.escape(value, quote=True)
    return PLACEHOLDER.sub(sub, template)


def load_strings(languages: list[str]) -> dict[str, dict[str, str]]:
    strings = {lang: json.loads((SITE / "strings" / f"{lang}.json").read_text(encoding="utf-8")) for lang in languages}
    reference = set(strings[languages[0]])
    for lang in languages[1:]:
        missing, extra = reference - set(strings[lang]), set(strings[lang]) - reference
        if missing or extra:
            sys.exit(f"site/strings/{lang}.json differs from {languages[0]}.json: missing {sorted(missing)}, extra {sorted(extra)}")
    return strings


def fonts_html(config: dict, out: Path, self_host: bool) -> str:
    """The <link> tags for the fonts. Self-hosted: one fonts.css, relative to the page via {{root}}."""
    symbols = SYMBOLS_CSS.format(icons=",".join(sorted(config["icons"])))
    if not self_host:
        return (f'<link rel="stylesheet" href="{html.escape(ROBOTO_CSS)}">\n'
                f'<link rel="stylesheet" href="{html.escape(symbols)}">')
    fonts_dir = out / "fonts"
    fonts_dir.mkdir(parents=True, exist_ok=True)
    css_parts = []
    for url, keep_subsets in ((ROBOTO_CSS, True), (symbols, False)):
        css = fetch(url).decode("utf-8")
        if keep_subsets:
            # Each @font-face is preceded by a /* subset */ comment; keep the Latin ones only.
            blocks = re.findall(r"/\*\s*([\w-]+)\s*\*/\s*(@font-face\s*\{[^}]*\})", css)
            css = "\n".join(block for subset, block in blocks if subset in FONT_SUBSETS)
        for font_url in sorted(set(re.findall(r"url\((https://[^)]+)\)", css))):
            local = f"{len(list(fonts_dir.iterdir())):02d}.woff2"
            (fonts_dir / local).write_bytes(fetch(font_url))
            css = css.replace(font_url, f"fonts/{local}")
        css_parts.append(css)
    (out / "fonts.css").write_text("\n".join(css_parts) + "\n", encoding="utf-8")
    return '<link rel="stylesheet" href="{{root}}fonts.css">'


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": BROWSER_UA})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def render_privacy() -> str:
    try:
        import markdown
    except ImportError:
        sys.exit("The privacy page needs the markdown package: pip install markdown")
    text = (ROOT / "docs" / "PRIVACY.md").read_text(encoding="utf-8")
    body = markdown.markdown(text, extensions=["tables", "toc"])
    # Wide tables scroll inside their own box on a phone instead of widening the page.
    return re.sub(r"<table>(.*?)</table>", r'<div class="table-wrap"><table>\1</table></div>', body, flags=re.S)


def build(out: Path, self_host_fonts: bool) -> None:
    config = json.loads((SITE / "site.json").read_text(encoding="utf-8"))
    languages: list[str] = config["languages"]
    strings = load_strings(languages)
    site_url: str = config["site_url"]

    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    shutil.copytree(SITE / "assets", out / "assets")
    shutil.copy2(SITE / "style.css", out / "style.css")
    (out / "screenshots").mkdir()
    for name in config["screenshots"].values():
        shutil.copy2(ROOT / "docs" / "screenshots" / name, out / "screenshots" / name)
    # GitHub Pages must serve the files as they are, not through Jekyll.
    (out / ".nojekyll").write_text("", encoding="utf-8")

    fonts = fonts_html(config, out, self_host_fonts)
    package = config["package"]
    links = {
        "site_url": site_url,
        "group_url": config["group_url"],
        "repo_url": config["repo_url"],
        "opt_in_url": f"https://play.google.com/apps/testing/{package}",
        "store_url": f"https://play.google.com/store/apps/details?id={package}",
    }
    links.update({f"shot_{key}": name for key, name in config["screenshots"].items()})

    def page_path(lang: str) -> str:
        return "" if lang == languages[0] else f"{lang}/"

    hreflang = "\n".join(
        [f'<link rel="alternate" hreflang="{lang}" href="{site_url}{page_path(lang)}">' for lang in languages]
        + [f'<link rel="alternate" hreflang="x-default" href="{site_url}">'])
    template = (SITE / "template.html").read_text(encoding="utf-8")

    for lang in languages:
        root = "" if lang == languages[0] else "../"
        langs_html = "\n".join(
            f'<a href="{root}{page_path(other) or "./"}" hreflang="{other}" lang="{other}"'
            + (' aria-current="page"' if other == lang else "") + f">{other.upper()}</a>"
            for other in languages)
        values = {**strings[lang], **links, "lang": lang, "root": root,
                  "canonical_url": site_url + page_path(lang),
                  "hreflang_html": hreflang, "langs_html": langs_html,
                  "fonts_html": fonts.replace("{{root}}", root)}
        target = out / page_path(lang) / "index.html"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(fill(template, values, f"{lang} page"), encoding="utf-8")

    privacy_values = {**strings[languages[0]], **links, "root": "../",
                      "canonical_url": site_url + "privacy/",
                      "fonts_html": fonts.replace("{{root}}", "../"),
                      "privacy_html": render_privacy()}
    privacy = fill((SITE / "privacy.html").read_text(encoding="utf-8"), privacy_values, "privacy page")
    (out / "privacy").mkdir()
    (out / "privacy" / "index.html").write_text(privacy, encoding="utf-8")
    print(f"Built {len(languages)} language pages and the privacy page into {out}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", type=Path, default=ROOT / "_site", help="output folder (default: _site)")
    parser.add_argument("--self-host-fonts", action="store_true", help="download the fonts into the site")
    args = parser.parse_args()
    build(args.out.resolve(), args.self_host_fonts)


if __name__ == "__main__":
    main()
