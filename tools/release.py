"""Publishes the built APK as a GitHub release: stable, or a preview (GitHub pre-release).

    python tools/release.py --notes notes.md            # stable release   (tag vX.Y.Z)
    python tools/release.py --preview --notes notes.md  # preview release  (tag vX.Y.Z-preview.N)
    python tools/release.py --preview --dry-run         # check everything, publish nothing

Before running: bump versionCode/versionName in app/build.gradle.kts, commit and push, then build
the signed APK with `gradlew assembleRelease`. The script checks that

  * versionName matches the channel (stable: "1.2.0"; preview: "1.2.0-preview.1"),
  * the APK is signed and has exactly this versionName and versionCode,
  * the current commit is on GitHub and the tag does not exist yet,

then creates the tag and release on that commit and uploads the APK. Previews are published as
pre-releases (never marked "latest"), so only apps on the Preview update channel see them.

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
DEFAULT_APK = os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk')
PREVIEW = re.compile(r'^\d+\.\d+\.\d+-preview\.\d+$')
STABLE = re.compile(r'^\d+\.\d+\.\d+$')


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


def sdk_tool(name):
    """aapt2/apksigner from the newest build-tools of the SDK named in local.properties (optional)."""
    props = os.path.join(ROOT, 'local.properties')
    if not os.path.exists(props):
        return None
    m = re.search(r'^sdk\.dir=(.+)$', open(props, encoding='utf-8').read(), re.M)
    if not m:
        return None
    bt = os.path.join(m.group(1).replace('\\:', ':').replace('\\\\', '\\').strip(), 'build-tools')
    if not os.path.isdir(bt):
        return None
    for version in sorted(os.listdir(bt), reverse=True):
        for candidate in (name + '.exe', name + '.bat', name):
            path = os.path.join(bt, version, candidate)
            if os.path.exists(path):
                return path
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


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--preview', action='store_true', help='publish as a preview (GitHub pre-release)')
    ap.add_argument('--notes', help='Markdown file with the release notes')
    ap.add_argument('--apk', default=DEFAULT_APK, help='APK to upload (default: the assembleRelease output)')
    ap.add_argument('--dry-run', action='store_true', help='run every check but publish nothing')
    args = ap.parse_args()

    code, name = gradle_versions()
    channel = 'preview' if args.preview else 'stable'
    if args.preview and not PREVIEW.match(name):
        fail(f'versionName "{name}" is not a preview version; use e.g. "0.2.0-preview.1"')
    if not args.preview and not STABLE.match(name):
        fail(f'versionName "{name}" is not a stable version; use e.g. "0.2.0" (or pass --preview)')
    tag = 'v' + name
    print(f'{channel} release {tag} (versionCode {code})')

    if not os.path.exists(args.apk):
        fail(f'{args.apk} not found: run gradlew assembleRelease first')
    check_apk(args.apk, code, name)

    if git('status', '--porcelain', '--untracked-files=no'):
        fail('uncommitted changes: commit them first, so the release matches the tagged code')
    head = git('rev-parse', 'HEAD')
    git('fetch', '-q', 'origin')
    if not git('branch', '-r', '--contains', head):
        fail('the current commit is not on GitHub yet: git push first')

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
    upload = release['upload_url'].split('{')[0] + '?name=' + asset_name
    with open(args.apk, 'rb') as f:
        asset = api(tok, upload, f.read(), 'POST', 'application/vnd.android.package-archive')
    git('fetch', '-q', '--tags', 'origin')
    print('published:', release['html_url'])
    print('apk:', asset['browser_download_url'])


if __name__ == '__main__':
    main()
