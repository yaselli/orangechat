#!/usr/bin/env python3
"""Name update APKs from their actual Android metadata, without publishing anything."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def apk_identity(badging, channel):
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match:
        raise ValueError("APK package/version metadata is missing")
    package, code_text, version = match.groups()
    expected_package = "me.rerere.orangechat" + (".debug" if channel == "debug" else "")
    pattern = r"\d+\.\d+\.\d+-debug\.\d+" if channel == "debug" else r"\d+\.\d+\.\d+"
    if package != expected_package or not re.fullmatch(pattern, version):
        raise ValueError("APK package or version does not match the update channel")
    if ("application-debuggable" in badging) != (channel == "debug"):
        raise ValueError("APK debug flag does not match the update channel")
    code = int(code_text)
    if not 0 < code <= 2100000000:
        raise ValueError("Invalid Android version code")
    native = re.search(r"^native-code:(.+)$", badging, re.M)
    abis = re.findall(r"'([^']+)'", native.group(1)) if native else []
    if not abis or any(abi not in {"arm64-v8a", "x86_64"} for abi in abis):
        raise ValueError("Unsupported or missing APK architecture")
    abi = abis[0] if len(abis) == 1 else "universal"
    return {"version": version, "versionCode": code, "abi": abi}


def file_sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verify_artifact(directory, channel, source_commit):
    root = Path(directory)
    metadata = json.loads((root / "release-metadata.json").read_text())
    version = metadata.get("version", "")
    pattern = r"\d+\.\d+\.\d+-debug\.\d+" if channel == "debug" else r"\d+\.\d+\.\d+"
    code = metadata.get("versionCode")
    if (not re.fullmatch(pattern, version) or metadata.get("tag") != "v" + version
            or type(code) is not int or not 0 < code <= 2100000000
            or metadata.get("channel") != channel or metadata.get("sourceCommit") != source_commit):
        raise ValueError("Artifact version/channel/source does not match the successful build")
    assets = metadata.get("assets", [])
    names = [item["name"] for item in assets]
    allowed_names = {f"Xiguaban-{version}-{code}-{abi}-{channel}.apk"
                     for abi in ["arm64-v8a", "x86_64", "universal"]}
    if (not names or len(set(names)) != len(names) or not set(names) <= allowed_names
            or set(names) != {p.name for p in root.glob("*.apk")}):
        raise ValueError("Artifact APK list is invalid")
    for item in assets:
        if file_sha256(root / item["name"]) != item["sha256"]:
            raise ValueError("Artifact APK checksum mismatch")
    return metadata


def prepare(apk_dir, output_dir, channel, aapt, source_commit):
    apks = sorted(Path(apk_dir).glob("*.apk"))
    if not apks:
        raise ValueError("No APKs found")
    records = [(apk, apk_identity(subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True), channel))
               for apk in apks]
    first = records[0][1]
    if any((item["version"], item["versionCode"]) != (first["version"], first["versionCode"])
           for _, item in records):
        raise ValueError("Do not mix APK versions in one release")
    if len({item["abi"] for _, item in records}) != len(records):
        raise ValueError("Duplicate APK architecture")
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise ValueError("A full source commit SHA is required")
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    assets = []
    for apk, item in records:
        name = f"Xiguaban-{item['version']}-{item['versionCode']}-{item['abi']}-{channel}.apk"
        shutil.copy2(apk, output / name)
        assets.append({"name": name, "sha256": file_sha256(apk)})
    metadata = {"version": first["version"], "versionCode": first["versionCode"],
                "tag": "v" + first["version"], "channel": channel, "sourceCommit": source_commit, "assets": assets}
    (output / "release-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--channel", choices=["release", "debug"], required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--source-commit", default=os.environ.get("GITHUB_SHA", ""))
    args = parser.parse_args()
    print(json.dumps(prepare(args.apk_dir, args.output_dir, args.channel, args.aapt, args.source_commit)))
