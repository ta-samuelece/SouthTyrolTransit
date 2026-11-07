"""Publishes the built GitHub APK as a GitHub release: stable, or a preview (GitHub pre-release).

    python tools/release.py --notes notes.md            # stable release   (tag vX.Y.Z)
    python tools/release.py --preview --notes notes.md  # preview release  (tag vX.Y.Z-preview.N)
    python tools/release.py --preview --dry-run         # check everything, publish nothing

The Release workflow (.github/workflows/release.yml) runs this same script on GitHub, with the notes
from docs/release-notes/<versionName>.md.

Before running: bump versionCode/versionName in app/build.gradle.kts, merge it into main through a
pull request, then build the signed APK from main with `gradlew assembleGithubRelease`. The script checks that

  * versionName matches the channel (stable: "1.2.0"; preview: "1.2.0-preview.1"),
  * the APK is signed and has exactly this versionName and versionCode,
  * the current commit is on origin/main and the tag does not exist yet,
  * the notes are complete: a stable release needs <version>.md, .de.md, .it.md and .highlights.json next
    to each other; a preview needs only the .md, and anything else it has is checked the same way,

then creates the tag and release on that commit and uploads whats-new.json (the cards and the translated
notes, for the app's update sheet) and the APK. Previews are published as pre-releases (never marked
"latest"), so only apps on the Preview update channel see them.

    python tools/release.py --check-notes --notes docs/release-notes/<version>.md

runs only the channel and notes checks, without an APK; the Release workflow starts with it.

    python tools/release.py --check-notes --notes docs/release-notes/<version>.md --play-notes whats-new.txt

also writes Play's "What's new" text for the Play bundle (`gradlew bundlePlayRelease`), in the
<en-US>...</en-US> form the Play Console accepts pasted: the cards as "Title: body" lines, as many whole
cards as fit Play's limit, or the notes' bullets for a version without cards. Publishing to Play is by hand.

GitHub access uses the credential git already has for github.com (or the GITHUB_TOKEN variable);
the token is never printed.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'github', 'release', 'app-github-release.apk')
PREVIEW = re.compile(r'^\d+\.\d+\.\d+-preview\.\d+$')
STABLE = re.compile(r'^\d+\.\d+\.\d+$')
LANGUAGES = ('en', 'de', 'it')
WHATS_NEW_ASSET = 'whats-new.json'  # the app looks for this exact name
PLAY_LIMIT = 500  # characters of Play's "What's new" text, per language
PLAY_LANGUAGES = {'en': 'en-US', 'de': 'de-DE', 'it': 'it-IT'}


def fail(msg):
    sys.exit('release: ' + msg)


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def gradle_versions():
    text = open(os.path.join(ROOT, 'app', 'build.gradle.kts'), encoding='utf-8').read()
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        fail('versionCode/versionName not found in app/build.gradle.kts')
    return int(code.group(1)), name.group(1)


def repo_slug():
    url = git('remote', 'get-url', 'origin')
    m = re.search(r'github\.com[:/](.+?)(?:\.git)?$', url)
    if not m:
        fail('origin is not a GitHub repository: ' + url)
    return m.group(1)


def token():
    if os.environ.get('GITHUB_TOKEN'):
        return os.environ['GITHUB_TOKEN']
    out = subprocess.run(['git', 'credential', 'fill'], input='protocol=https\nhost=github.com\n\n',
                         capture_output=True, text=True, env={**os.environ, 'GIT_TERMINAL_PROMPT': '0'}).stdout
    t = dict(line.split('=', 1) for line in out.splitlines() if '=' in line).get('password')
    if not t:
        fail('no GitHub credential: push once with git, or set GITHUB_TOKEN')
    return t


def api(tok, url, data=None, method='GET', content_type='application/json'):
    req = urllib.request.Request(url, data=data, method=method, headers={
        'Authorization': 'Bearer ' + tok, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28',
        'Content-Type': content_type, 'User-Agent': 'southtyroltransit-release'})
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            return json.loads(r.read() or b'null')
    except urllib.error.HTTPError as e:
        if e.code == 404 and method == 'GET':
            return None
        fail(f'GitHub API {method} {url.split("?")[0]} failed: HTTP {e.code} {e.read()[:300]!r}')


def sdk_dir():
    props = os.path.join(ROOT, 'local.properties')
    if os.path.exists(props):
        m = re.search(r'^sdk\.dir=(.+)$', open(props, encoding='utf-8').read(), re.M)
        if m:
            return m.group(1).replace('\\:', ':').replace('\\\\', '\\').strip()
    return os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')


def sdk_tool(name):
    """aapt2/apksigner from the newest build-tools of the SDK in local.properties or ANDROID_HOME.

    Optional on a workstation; on CI (the release workflow) a missing tool is an error, since nobody
    would read the "skipped" line."""
    sdk = sdk_dir()
    bt = os.path.join(sdk, 'build-tools') if sdk else None
    if not bt or not os.path.isdir(bt):
        if os.environ.get('CI'):
            fail(f'{name} not found: no Android SDK build-tools on this runner')
        return None
    for version in sorted(os.listdir(bt), reverse=True):
        for candidate in (name + '.exe', name + '.bat', name):
            path = os.path.join(bt, version, candidate)
            if os.path.exists(path):
                return path
    if os.environ.get('CI'):
        fail(f'{name} not found in {bt}')
    return None


def check_apk(apk, code, name):
    aapt2 = sdk_tool('aapt2')
    if aapt2:
        badging = subprocess.run([aapt2, 'dump', 'badging', apk], capture_output=True, text=True).stdout
        m = re.search(r"versionCode='(\d+)' versionName='([^']+)'", badging)
        if not m or (int(m.group(1)), m.group(2)) != (code, name):
            fail(f'APK is {m.groups() if m else "unreadable"}, expected versionCode {code} / versionName {name}: rebuild it')
    else:
        print('  (aapt2 not found: skipped APK version check)')
    signer = sdk_tool('apksigner')
    if signer:
        out = subprocess.run([signer, 'verify', '--print-certs', apk], capture_output=True, text=True, shell=signer.endswith('.bat'))
        text = out.stdout + out.stderr
        if 'JAVA_HOME' in text or 'java' in text.lower() and 'not found' in text.lower():
            fail('apksigner needs Java: set JAVA_HOME (e.g. to Android Studio\'s jbr folder) and run again')
        if out.returncode != 0 or 'Signer' not in text:
            fail('APK is not signed (set the release.* lines in local.properties and rebuild)')
        digest = re.search(r'SHA-256 digest: (\w+)', out.stdout)
        print('  signed, certificate SHA-256', digest.group(1)[:16] + '...' if digest else '?')
    else:
        print('  (apksigner not found: skipped signature check)')


def read_text(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def whats_new(notes_path, name, preview):
    """The content of whats-new.json, or None when a preview has nothing beyond its English notes.

    The files sit next to the English notes: <version>.de.md, <version>.it.md, <version>.highlights.json.
    Fails on anything a stable release lacks, and on any highlights file that is malformed."""
    folder = os.path.dirname(os.path.abspath(notes_path))
    path = lambda suffix: os.path.join(folder, name + suffix)
    required = ('en',) if preview else LANGUAGES
    notes = {}
    for language in LANGUAGES:
        file = path('.md') if language == 'en' else path(f'.{language}.md')
        text = read_text(file).strip() if os.path.exists(file) else ''
        if text:
            notes[language] = text
        elif language in required:
            fail(f'{file} is missing or empty')

    highlights = []
    file = path('.highlights.json')
    if os.path.exists(file):
        highlights = check_highlights(file, required)
    elif not preview:
        fail(f'{file} is missing: a stable release needs its highlight cards')

    if preview and not highlights and list(notes) == ['en']:
        return None
    return {'version': name, 'highlights': highlights, 'notes': notes}


def check_highlights(file, languages):
    try:
        highlights = json.loads(read_text(file)).get('highlights')
    except (ValueError, AttributeError) as e:
        fail(f'{file} is not readable: {e}')
    if not isinstance(highlights, list) or not highlights:
        fail(f'{file} needs a non-empty "highlights" list')
    known = set(read_text(os.path.join(os.path.dirname(file), 'icons.txt')).split())
    for i, card in enumerate(highlights, 1):
        if card.get('icon') not in known:
            fail(f'{file}: card {i} has the unknown icon {card.get("icon")!r}; see icons.txt')
        for field in ('title', 'body'):
            for language in languages:
                if not str(card.get(field, {}).get(language, '')).strip():
                    fail(f'{file}: card {i} has no {field} in {language}')
    return highlights


def play_text(cards, notes, limit=PLAY_LIMIT):
    """One language's "What's new" for Play: whole cards as "Title: body" lines while they fit [limit].

    Without cards (a preview, or a hotfix), the bullets of the notes, again only whole ones."""
    lines = [f'{c["title"]}: {c["body"]}'.strip() for c in cards] if cards else [
        re.sub(r'\*\*|__|\[([^\]]*)\]\([^)]*\)', lambda m: m.group(1) or '', line.strip()[2:].strip())
        for line in notes.splitlines() if line.strip().startswith(('- ', '* '))]
    text = ''
    for line in lines:
        candidate = f'{text}\n{line}' if text else line
        if len(candidate) > limit:
            break
        text = candidate
    return text


def play_whats_new(extra, name):
    """Play's "What's new" in every language there is text for, in the Play Console's pasted form."""
    notes = extra['notes'] if extra else {'en': ''}
    blocks = []
    for language, tag in PLAY_LANGUAGES.items():
        if language not in notes:
            continue
        cards = [{field: card[field].get(language) or card[field].get('en', '') for field in ('title', 'body')}
                 for card in (extra['highlights'] if extra else [])]
        text = play_text(cards, notes[language])
        if not text:
            fail(f'no Play "What\'s new" text for {name} in {language}: the first card or bullet is over {PLAY_LIMIT} characters')
        blocks.append(f'<{tag}>\n{text}\n</{tag}>')
    return '\n'.join(blocks) + '\n'


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--preview', action='store_true', help='publish as a preview (GitHub pre-release)')
    ap.add_argument('--notes', help='Markdown file with the release notes')
    ap.add_argument('--apk', default=DEFAULT_APK, help='APK to upload (default: the assembleGithubRelease output)')
    ap.add_argument('--play-notes', metavar='FILE', help='also write Play\'s "What\'s new" text to FILE')
    ap.add_argument('--dry-run', action='store_true', help='run every check but publish nothing')
    ap.add_argument('--check-notes', action='store_true', help='check only the channel and the notes, then stop')
    args = ap.parse_args()

    code, name = gradle_versions()
    channel = 'preview' if args.preview else 'stable'
    if args.preview and not PREVIEW.match(name):
        fail(f'versionName "{name}" is not a preview version; use e.g. "0.2.0-preview.1"')
    if not args.preview and not STABLE.match(name):
        fail(f'versionName "{name}" is not a stable version; use e.g. "0.2.0" (or pass --preview)')
    tag = 'v' + name
    print(f'{channel} release {tag} (versionCode {code})')

    if not args.notes and not args.preview:
        fail('a stable release needs --notes')
    extra = whats_new(args.notes, name, args.preview) if args.notes else None
    print(f'  {WHATS_NEW_ASSET}: {len(extra["highlights"])} cards, notes in {", ".join(extra["notes"])}' if extra else f'  no {WHATS_NEW_ASSET}: plain notes')
    if args.play_notes:
        plain = extra or ({'highlights': [], 'notes': {'en': read_text(args.notes)}} if args.notes else None)
        if not plain:
            fail('--play-notes needs --notes')
        with open(args.play_notes, 'w', encoding='utf-8', newline='\n') as f:
            f.write(play_whats_new(plain, name))
        print(f'  Play "What\'s new": {args.play_notes}')
    if args.check_notes:
        return

    if not os.path.exists(args.apk):
        fail(f'{args.apk} not found: run gradlew assembleGithubRelease first')
    check_apk(args.apk, code, name)

    if git('status', '--porcelain', '--untracked-files=no'):
        fail('uncommitted changes: commit them first, so the release matches the tagged code')
    head = git('rev-parse', 'HEAD')
    git('fetch', '-q', 'origin')
    # main is protected: releases come only from merged work, never from a pushed feature branch.
    if subprocess.run(['git', 'merge-base', '--is-ancestor', head, 'origin/main'], cwd=ROOT).returncode != 0:
        fail('the current commit is not on origin/main yet: merge its pull request first')

    slug = repo_slug()
    tok = token()
    base = f'https://api.github.com/repos/{slug}'
    if api(tok, f'{base}/releases/tags/{tag}') is not None:
        fail(f'release {tag} already exists')
    if api(tok, f'{base}/git/ref/tags/{tag}') is not None:
        fail(f'tag {tag} already exists on GitHub')

    notes = open(args.notes, encoding='utf-8').read() if args.notes else ''
    if args.preview:
        notes = ('> **Preview build.** Offered in the app only on the *Preview* update channel '
                 '(Settings → App updates). It may contain bugs.\n\n' + notes)
    # The preview's version already says "preview"; stable builds get a "-release" suffix.
    asset_name = f'SouthTyrolTransit-{name}.apk' if args.preview else f'SouthTyrolTransit-{name}-release.apk'
    print(f'  commit {head[:7]}, asset {asset_name}, {os.path.getsize(args.apk) // 1_048_576} MB')
    if args.dry_run:
        print('dry run: nothing published')
        return

    release = api(tok, f'{base}/releases', json.dumps({
        'tag_name': tag, 'target_commitish': head, 'name': f'South Tyrol Transit {name}', 'body': notes,
        'draft': False, 'prerelease': args.preview, 'make_latest': 'false' if args.preview else 'true',
    }).encode(), 'POST')
    upload_base = release['upload_url'].split('{')[0] + '?name='
    # Before the APK: the app offers a release only once its APK is there, so it never misses this file.
    if extra:
        api(tok, upload_base + WHATS_NEW_ASSET, json.dumps(extra, ensure_ascii=False).encode('utf-8'), 'POST')
    upload = upload_base + asset_name
    with open(args.apk, 'rb') as f:
        asset = api(tok, upload, f.read(), 'POST', 'application/vnd.android.package-archive')
    git('fetch', '-q', '--tags', 'origin')
    print('published:', release['html_url'])
    print('apk:', asset['browser_download_url'])


if __name__ == '__main__':
    main()
