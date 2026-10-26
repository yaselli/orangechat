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
APPROVED_REPOSITORIES = frozenset({'yaselli/watermelon-chat', 'yaselli/orangechat'})
ALLOWED_RELEASE_FILES = {
    'app/build.gradle.kts', '.github/release-plan.json',
    '.github/workflows/build-apk.yml', '.github/workflows/build-isolated-apk.yml',
    'docs/releases/2.3.2.md', 'docs/releases/2.3.3.md', 'docs/releases/2.3.4.md', 'docs/app-updates.md',
    'scripts/publish-approved-update.py', 'scripts/test-publish-approved-update.py',
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


def approved_rebuild(channel, env=os.environ, run=command):
    """Reuse full checks only for an unchanged, accepted application and an exact successful CI source."""
    if (env.get('GITHUB_REPOSITORY') not in APPROVED_REPOSITORIES
            or env.get('GITHUB_REF') != 'refs/heads/build/stable-release'
            or env.get('GITHUB_EVENT_NAME') != 'push'):
        return False
    plan = json.loads((ROOT / '.github/release-plan.json').read_text())
    config = (ROOT / 'app/build.gradle.kts').read_text()
    version = re.search(r'versionName\s*=\s*"([^"\n]+)"', config)
    code = re.search(r'versionCode\s*=\s*(\d+)', config)
    if not version or not code or version.group(1) != plan['version'] or int(code.group(1)) != plan['versionCode']:
        return False
    try:
        verify_source(plan['testedCommit'], env['GITHUB_SHA'], run)
        run_id = plan['verifiedRuns'][channel]
        if type(run_id) is not int or run_id <= 0:
            return False
        previous = json.loads(run(['gh', 'api', f'repos/{env["GITHUB_REPOSITORY"]}/actions/runs/{run_id}']).stdout)
        expected = '.github/workflows/' + ('build-isolated-apk.yml' if channel == 'release' else 'build-apk.yml')
        return (previous.get('status') == 'completed' and previous.get('conclusion') == 'success'
                and previous.get('head_sha') == plan['testedCommit'] and previous.get('path') == expected
                and previous.get('head_repository', {}).get('full_name') == env['GITHUB_REPOSITORY'])
    except (RuntimeError, ValueError, KeyError):
        # No proof means normal full verification, never a successful shortcut.
        return False


def publish(directory, channel, env=os.environ, run=command):
    if (env.get('GITHUB_REPOSITORY') not in APPROVED_REPOSITORIES
            or env.get('GITHUB_REF') != 'refs/heads/build/stable-release'
            or env.get('GITHUB_EVENT_NAME') != 'push'):
        raise ValueError('Publishing is restricted to stable-branch pushes in the approved repository')
    plan = json.loads((ROOT / '.github/release-plan.json').read_text())
    source = json.loads((Path(directory) / 'release-metadata.json').read_text())['sourceCommit']
    if source != env['GITHUB_SHA']:
        reuse = plan.get('reuseArtifact', {}).get(channel, {})
        if source != reuse.get('source'):
            raise ValueError('Artifact is not the approved reusable build')
        verify_source(source, env['GITHUB_SHA'], run)
    verify = runpy.run_path(str(ROOT / 'scripts/prepare-update-assets.py'))['verify_artifact']
    metadata = verify(directory, channel, source)
    version = plan['version'] + ('-debug.1' if channel == 'debug' else '')
    if (metadata['version'] != version or metadata['versionCode'] != plan['versionCode']
            or channel not in plan['channels']):
        print('No matching approved release plan; nothing published')
        return False
    verify_source(plan['testedCommit'], source, run)
    notes = ROOT / plan['notes']
    if plan['notes'] != f'docs/releases/{plan["version"]}.md' or not notes.is_file():
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
    uploaded = json.loads(run(['gh', 'release', 'view', tag, '--repo', repo, '--json', 'assets']).stdout)
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
    parser.add_argument('--directory')
    parser.add_argument('--check-rebuild', action='store_true')
    parser.add_argument('--channel', choices=['release', 'debug'], required=True)
    args = parser.parse_args()
    if args.check_rebuild:
        print('approved=' + str(approved_rebuild(args.channel)).lower())
    else:
        if not args.directory:
            parser.error('--directory is required for publication')
        publish(args.directory, args.channel)
