#!/usr/bin/env python3
"""Report the built APK's actual ZIP footprint and guard removed native engines."""
import argparse
import json
from pathlib import Path
import zipfile


def inspect(apk):
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
    removed = {"libmupdf_java.so", "libucrop.so", "libbarhopper_v3.so"}
    unexpected = [entry.filename for entry in entries if Path(entry.filename).name in removed]
    if unexpected:
        raise ValueError(f"Replaced native dependencies remain in {apk.name}: {unexpected}")
    categories = {}
    for entry in entries:
        category = "native" if entry.filename.startswith("lib/") else (
            "dex" if entry.filename.endswith(".dex") else (
                "assets" if entry.filename.startswith("assets/") else "resources_and_other"
            )
        )
        group = categories.setdefault(category, {"compressed_bytes": 0, "uncompressed_bytes": 0})
        group["compressed_bytes"] += entry.compress_size
        group["uncompressed_bytes"] += entry.file_size
    return {
        "apk": apk.name,
        "apk_bytes": apk.stat().st_size,
        "abis": sorted({entry.filename.split("/")[1] for entry in entries
                        if entry.filename.startswith("lib/") and entry.filename.count("/") >= 2}),
        "categories": categories,
        "native_libraries": [entry.filename for entry in entries if entry.filename.startswith("lib/")],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    apks = sorted(args.apk_dir.glob("*.apk"))
    if not apks:
        parser.error(f"No APKs in {args.apk_dir}")
    reports = [inspect(apk) for apk in apks]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(reports, ensure_ascii=False, indent=2) + "\n")
    for report in reports:
        print(f"{report['apk']}: {report['apk_bytes']:,} bytes; ABIs={','.join(report['abis'])}")


if __name__ == "__main__":
    main()
