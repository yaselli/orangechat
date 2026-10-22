#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("update_assets", Path(__file__).with_name("prepare-update-assets.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def badging(package="me.rerere.orangechat", code="165", version="2.3.0", abi="arm64-v8a", debug=False):
    return (f"package: name='{package}' versionCode='{code}' versionName='{version}'\n"
            + ("application-debuggable\n" if debug else "") + f"native-code: '{abi}'\n")


class UpdateAssetsTest(unittest.TestCase):
    def test_release_identity(self):
        self.assertEqual(module.apk_identity(badging(), "release"),
                         {"version": "2.3.0", "versionCode": 165, "abi": "arm64-v8a"})

    def test_debug_identity(self):
        value = badging(package="me.rerere.orangechat.debug", version="2.3.0-debug.1", debug=True, abi="x86_64")
        self.assertEqual(module.apk_identity(value, "debug")["abi"], "x86_64")

    def test_reject_cross_channel_and_bad_metadata(self):
        for value in [badging(package="other.package"), badging(package="me.rerere.orangechat.debug"),
                      badging(version="2.3.0-debug.1"), badging(version="../../file"), badging(debug=True),
                      badging(code="0"), badging(code="9999999999"), badging(abi="armeabi-v7a"), "not an apk"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.apk_identity(value, "release")

    def test_debug_cannot_advertise_release_package(self):
        with self.assertRaises(ValueError):
            module.apk_identity(badging(), "debug")

    def test_prepare_verify_and_reject_tampering(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, output = root / "apks", root / "output"
            source.mkdir()
            (source / "app.apk").write_bytes(b"test APK bytes")
            commit = "a" * 40
            with patch.object(module.subprocess, "check_output", return_value=badging()):
                metadata = module.prepare(source, output, "release", "aapt", commit)
            self.assertEqual(module.verify_artifact(output, "release", commit), metadata)
            with self.assertRaises(ValueError):
                module.verify_artifact(output, "debug", commit)
            with self.assertRaises(ValueError):
                module.verify_artifact(output, "release", "b" * 40)
            (output / metadata["assets"][0]["name"]).write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                module.verify_artifact(output, "release", commit)

    def test_reject_mixed_versions_and_duplicate_abis(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "a.apk").write_bytes(b"a")
            (root / "b.apk").write_bytes(b"b")
            for identities in [[badging(), badging(version="2.3.1", abi="x86_64")], [badging(), badging()]]:
                with patch.object(module.subprocess, "check_output", side_effect=identities):
                    with self.assertRaises(ValueError):
                        module.prepare(root, root / "output", "release", "aapt", "a" * 40)


if __name__ == "__main__":
    unittest.main()
