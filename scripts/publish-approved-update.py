#!/usr/bin/env python3
"""Publish only the accepted release plan, after its stable-branch build job has succeeded."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_RELEASE_FILES = {
    'app/build.gradle.kts', '.github/release-plan.json',
    '.github/workflows/build-apk.yml', '.github/workflows/build-isolated-apk.yml',
    'docs/releases/2.3.2.md', 'scripts/publish-approved-update.py', 'scripts/test-publish-approved-update.py',
}


def command(args, check=True):
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or 'Release command failed')
    return result


def verify_source(tested, source, run=command):
    if not re.fullmatch('[0-9a-f]{40}', tested) or not re.fullmatch('[0-9a-f]{40}', source):
        raise ValueError('Invalid source commit')
    run(['git', 'merge-base', '--is-ancestor', tested, source])
    changed = set(run(['git', 'diff', '--name-only', tested, source]).stdout.splitlines())
    if changed - ALLOWED_RELEASE_FILES:
        raise ValueError('Untested application changes are not part of this release approval')
    version_diff = run(['git', 'diff', '--unified=0', tested, source, '--', 'app/build.gradle.kts']).stdout
    changes = [line for line in version_diff.splitlines()
               if line.startswith(('+', '-')) and not line.startswith(('+++', '---'))]
    if any(not re.fullmatch(r'[+-]\s*version(?:Code\s*=\s*\d+|Name\s*=\s*"\d+\.\d+\.\d+")', line)
           for line in changes):
        raise ValueError('Only version constants may change in the accepted app build configuration')


def publish(directory, channel, env=os.environ, run=command):
    if (env.get('GITHUB_REPOSITORY') != 'yaselli/orangechat'
            or env.get('GITHUB_REF') != 'refs/heads/build/stable-release'
            or env.get('GITHUB_EVENT_NAME') != 'push'):
        raise ValueError('Publishing is restricted to stable-branch pushes in the approved repository')
    plan = json.loads((ROOT / '.github/release-plan.json').read_text())
    source = env['GITHUB_SHA']
    verify = runpy.run_path(str(ROOT / 'scripts/prepare-update-assets.py'))['verify_artifact']
    metadata = verify(directory, channel, source)
    version = plan['version'] + ('-debug.1' if channel == 'debug' else '')
    if (metadata['version'] != version or metadata['versionCode'] != plan['versionCode']
            or channel not in plan['channels']):
        print('No matching approved release plan; nothing published')
        return False
    verify_source(plan['testedCommit'], source, run)
    notes = ROOT / plan['notes']
    if plan['notes'] != 'docs/releases/2.3.2.md' or not notes.is_file():
        raise ValueError('Approved release notes are missing')
    repo, tag = env['GITHUB_REPOSITORY'], metadata['tag']
    tag_commit = run(['gh', 'api', f'repos/{repo}/git/ref/tags/{tag}'], check=False)
    if tag_commit.returncode:
        if '404' not in tag_commit.stderr:
            raise RuntimeError('Unable to verify release tag')
        run(['gh', 'api', '--method', 'POST', f'repos/{repo}/git/refs',
             '-f', f'ref=refs/tags/{tag}', '-f', f'sha={source}'])
    else:
        existing_source = run(['gh', 'api', f'repos/{repo}/commits/{tag}', '--jq', '.sha']).stdout.strip()
        if existing_source != source:
            raise ValueError('Existing tag points to another source commit')
    view = run(['gh', 'release', 'view', tag, '--repo', repo, '--json', 'isDraft,isPrerelease'], check=False)
    if view.returncode:
        if 'not found' not in view.stderr.lower() and '404' not in view.stderr:
            raise RuntimeError('Unable to check existing release')
        args = ['gh', 'release', 'create', tag, '--repo', repo, '--target', source, '--draft',
                '--title', '西瓜瓣 ' + version, '--notes-file', str(notes), '--verify-tag']
        if channel == 'debug':
            args.append('--prerelease')
        run(args)
        draft = True
    else:
        release = json.loads(view.stdout)
        if release['isPrerelease'] != (channel == 'debug'):
            raise ValueError('Existing release has the wrong update channel')
        draft = release['isDraft']
    # A pre-existing tag must not silently redirect this release to another commit.
    resolved = run(['gh', 'api', f'repos/{repo}/commits/{tag}', '--jq', '.sha']).stdout.strip()
    if resolved != source:
        raise ValueError('Release tag does not point to this verified build')
    root = Path(directory)
    assets = [root / item['name'] for item in metadata['assets']] + [root / 'release-metadata.json']
    if draft:
        run(['gh', 'release', 'upload', tag, '--repo', repo, '--clobber', *map(str, assets)])
    uploaded = json.loads(run(['gh', 'api', f'repos/{repo}/releases/tags/{tag}']).stdout)
    remote = {item['name']: item for item in uploaded['assets']}
    if set(remote) != {asset.name for asset in assets}:
        raise ValueError('Release assets do not match the verified artifact')
    with tempfile.TemporaryDirectory() as temporary:
        for asset in assets:
            if asset.name not in remote:
                raise ValueError('Release upload is incomplete')
            digest = remote[asset.name].get('digest')
            expected = 'sha256:' + hashlib.sha256(asset.read_bytes()).hexdigest()
            if not digest:
                run(['gh', 'release', 'download', tag, '--repo', repo, '--pattern', asset.name, '--dir', temporary])
                digest = 'sha256:' + hashlib.sha256((Path(temporary) / asset.name).read_bytes()).hexdigest()
            if digest != expected:
                raise ValueError('Uploaded asset checksum mismatch')
    if not draft:
        print('Matching release already published; leaving it unchanged')
        return True
    run(['gh', 'release', 'edit', tag, '--repo', repo, '--draft=false',
         '--latest=' + ('true' if channel == 'release' else 'false')])
    print(f'Published {tag} from {source}')
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--channel', choices=['release', 'debug'], required=True)
    args = parser.parse_args()
    publish(args.directory, args.channel)
