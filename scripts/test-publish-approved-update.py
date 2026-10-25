#!/usr/bin/env python3
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publish_update', Path(__file__).with_name('publish-approved-update.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SOURCE = 'a' * 40
ENV = {'GITHUB_REPOSITORY': 'yaselli/orangechat', 'GITHUB_REF': 'refs/heads/build/stable-release',
       'GITHUB_EVENT_NAME': 'push', 'GITHUB_SHA': SOURCE}


class FakeGithub:
    def __init__(self, directory, channel='release'):
        self.directory = directory
        self.calls = []
        self.tag = False
        self.created = False
        self.published = False
        self.channel = channel
        self.bad_digest = False
        self.tag_source = SOURCE

    def __call__(self, args, check=True):
        self.calls.append(args)
        out, error, code = '', '', 0
        if args[:2] == ['git', 'diff'] and '--name-only' in args:
            out = 'app/build.gradle.kts\nscripts/publish-approved-update.py\n'
        elif args[:2] == ['git', 'diff']:
            out = '-        versionCode = 166\n+        versionCode = 167\n'
        elif args[:3] == ['gh', 'release', 'view'] and args[-1] == 'assets':
            out = json.dumps({'assets': [
                {'name': file.name, 'digest': 'sha256:' + (
                    'bad' if self.bad_digest else hashlib.sha256(file.read_bytes()).hexdigest())}
                for file in self.directory.iterdir()
            ]})
        elif args[:3] == ['gh', 'release', 'view']:
            if self.created:
                out = json.dumps({'isDraft': not self.published, 'isPrerelease': self.channel == 'debug'})
            else:
                code, error = 1, 'release not found'
        elif args[:3] == ['gh', 'api', '--method']:
            self.tag = True
        elif args[:2] == ['gh', 'api'] and '/git/ref/tags/' in args[2]:
            if self.tag:
                out = json.dumps({'object': {'sha': self.tag_source}})
            else:
                code, error = 1, 'HTTP 404 Not Found'
        elif args[:2] == ['gh', 'api'] and '/commits/' in args[2]:
            if self.tag:
                out = self.tag_source
            else:
                code, error = 1, 'HTTP 404 Not Found'
        elif args[:3] == ['gh', 'release', 'create']:
            self.created = True
        elif args[:2] == ['gh', 'api'] and '/releases/tags/' in args[2]:
            out = json.dumps({'assets': [
                {'name': file.name, 'digest': 'sha256:' + (
                    'bad' if self.bad_digest else hashlib.sha256(file.read_bytes()).hexdigest())}
                for file in self.directory.iterdir()
            ]})
        elif args[:3] == ['gh', 'release', 'edit']:
            self.published = True
        if check and code:
            raise RuntimeError(error)
        return subprocess.CompletedProcess(args, code, out, error)


class PublishUpdateTest(unittest.TestCase):
    def artifact(self, root, channel='release'):
        plan = json.loads((module.ROOT / '.github/release-plan.json').read_text())
        version = plan['version'] + ('-debug.1' if channel == 'debug' else '')
        code = plan['versionCode']
        file = root / f'Xiguaban-{version}-{code}-arm64-v8a-{channel}.apk'
        file.write_bytes(b'fake signed APK')
        metadata = {'version': version, 'versionCode': code, 'tag': 'v' + version, 'channel': channel,
                    'sourceCommit': SOURCE, 'assets': [{'name': file.name,
                    'sha256': hashlib.sha256(file.read_bytes()).hexdigest()}]}
        (root / 'release-metadata.json').write_text(json.dumps(metadata))

    def test_rebuild_reuse_requires_both_unchanged_code_and_the_successful_exact_source(self):
        plan = json.loads((module.ROOT / '.github/release-plan.json').read_text())
        # A new release has no reusable runs. Exercise reuse against an isolated, explicit fixture.
        plan['verifiedRuns'] = {'release': 101, 'debug': 102}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.github').mkdir()
            (root / 'app').mkdir()
            (root / '.github/release-plan.json').write_text(json.dumps(plan))
            (root / 'app/build.gradle.kts').write_text(
                f'versionName = "{plan["version"]}"\nversionCode = {plan["versionCode"]}\n')
            with patch.object(module, 'ROOT', root):
                self.check_rebuild_reuse(plan)

    def check_rebuild_reuse(self, plan):
        for channel in ('release', 'debug'):
            state = {'conclusion': 'success'}
            def api(args, check=True):
                if '--name-only' in args:
                    out = 'app/build.gradle.kts\n'
                elif args[0] == 'git':
                    out = ''
                else:
                    out = json.dumps(dict(status='completed', conclusion=state['conclusion'],
                        head_sha=plan['testedCommit'],
                        path='.github/workflows/' + ('build-isolated-apk.yml' if channel == 'release' else 'build-apk.yml'),
                        head_repository={'full_name': ENV['GITHUB_REPOSITORY']}))
                return subprocess.CompletedProcess(args, 0, out, '')
            self.assertTrue(module.approved_rebuild(channel, ENV, api))
            state['conclusion'] = 'failure'
            self.assertFalse(module.approved_rebuild(channel, ENV, api))
            state['conclusion'] = 'success'
            def changed_code(args, check=True):
                if '--name-only' in args:
                    return subprocess.CompletedProcess(args, 0, 'ai/src/main/Provider.kt\n', '')
                return api(args, check)
            self.assertFalse(module.approved_rebuild(channel, ENV, changed_code))
            self.assertFalse(module.approved_rebuild(channel, dict(ENV, GITHUB_REF='refs/heads/master'), api))

    def test_missing_verified_runs_require_full_checks(self):
        plan = json.loads((module.ROOT / '.github/release-plan.json').read_text())
        plan['verifiedRuns'] = {'release': 0, 'debug': 0}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.github').mkdir()
            (root / 'app').mkdir()
            (root / '.github/release-plan.json').write_text(json.dumps(plan))
            (root / 'app/build.gradle.kts').write_text(
                f'versionName = "{plan["version"]}"\nversionCode = {plan["versionCode"]}\n')
            with patch.object(module, 'ROOT', root):
                def git_only(args, check=True):
                    self.assertEqual('git', args[0])
                    return subprocess.CompletedProcess(args, 0, '', '')
                for channel in ('release', 'debug'):
                    self.assertFalse(module.approved_rebuild(channel, ENV, git_only))

    def test_publish_order_channels_and_idempotent_retry(self):
        for channel in ('release', 'debug'):
            with self.subTest(channel=channel), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.artifact(root, channel)
                gh = FakeGithub(root, channel)
                self.assertTrue(module.publish(root, channel, ENV, gh))
                create = next(c for c in gh.calls if c[:3] == ['gh', 'release', 'create'])
                self.assertIn('--draft', create)
                self.assertIn('--verify-tag', create)
                self.assertEqual(channel == 'debug', '--prerelease' in create)
                upload_index = next(i for i, c in enumerate(gh.calls) if c[:3] == ['gh', 'release', 'upload'])
                publish_index = next(i for i, c in enumerate(gh.calls) if c[:3] == ['gh', 'release', 'edit'])
                self.assertLess(upload_index, publish_index)
                self.assertIn('--latest=' + ('true' if channel == 'release' else 'false'), gh.calls[publish_index])
                writes = sum(c[:3] == ['gh', 'release', 'edit'] for c in gh.calls)
                self.assertTrue(module.publish(root, channel, ENV, gh))
                self.assertEqual(writes, sum(c[:3] == ['gh', 'release', 'edit'] for c in gh.calls))

    def test_unapproved_branch_or_event_never_writes(self):
        for key, value in [('GITHUB_REF', 'refs/heads/master'), ('GITHUB_EVENT_NAME', 'pull_request'),
                           ('GITHUB_REPOSITORY', 'someone/else')]:
            with tempfile.TemporaryDirectory() as temp:
                gh = FakeGithub(Path(temp))
                with self.assertRaises(ValueError):
                    module.publish(Path(temp), 'release', dict(ENV, **{key: value}), gh)
                self.assertEqual([], gh.calls)

    def test_repository_rename_uses_only_the_approved_old_or_new_repository(self):
        for repo in ('yaselli/orangechat', 'yaselli/watermelon-chat'):
            with self.subTest(repo=repo), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.artifact(root)
                gh = FakeGithub(root)
                self.assertTrue(module.publish(root, 'release', dict(ENV, GITHUB_REPOSITORY=repo), gh))
                publish = next(c for c in gh.calls if c[:3] == ['gh', 'release', 'edit'])
                self.assertEqual(repo, publish[publish.index('--repo') + 1])

    def test_bad_local_or_uploaded_bytes_and_wrong_tag_cannot_publish(self):
        for failure in ('local', 'remote', 'tag'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.artifact(root)
                gh = FakeGithub(root)
                if failure == 'local':
                    next(root.glob('*.apk')).write_bytes(b'changed')
                elif failure == 'remote':
                    gh.bad_digest = True
                else:
                    gh.tag, gh.tag_source = True, 'b' * 40
                with self.assertRaises(ValueError):
                    module.publish(root, 'release', ENV, gh)
                self.assertFalse(gh.published)

    def test_unaccepted_functional_changes_are_rejected(self):
        def bad_source(args, check=True):
            if '--name-only' in args:
                return subprocess.CompletedProcess(args, 0, 'app/src/main/java/ChatService.kt\n', '')
            return subprocess.CompletedProcess(args, 0, '', '')
        with self.assertRaises(ValueError):
            module.verify_source('b' * 40, SOURCE, bad_source)
        def bad_build_change(args, check=True):
            out = 'app/build.gradle.kts\n' if '--name-only' in args else '+ targetSdk = 99\n'
            return subprocess.CompletedProcess(args, 0, out, '')
        with self.assertRaises(ValueError):
            module.verify_source('b' * 40, SOURCE, bad_build_change)


if __name__ == '__main__':
    unittest.main()
