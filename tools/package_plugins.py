"""Create standalone source plugin archives from explicit allowlists, without secrets or tests."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ROOT / "integrations"
ASTRBOT = ["__init__.py", "main.py", "metadata.yaml", "_conf_schema.json", "policy.py",
           "connector.py", "connector_chat.py", "README.md", "README.en.md", "CONNECTOR.md",
           "SUPPORTED.md", "COPYING", "THIRD_PARTY_NOTICES.md"]
OUTER_GO = ["install.mjs", "README.md", "README.en.md", "COPYING", "THIRD_PARTY_NOTICES.md"]


def declared(directory: Path) -> list[str]:
    package = json.loads((directory / "package.json").read_text(encoding="utf-8"))
    files = ["package.json", *package["files"]]
    if package.get("license") != "AGPL-3.0-only" or len(files) != len(set(files)):
        raise ValueError("Missing explicit project license or duplicated package.files")
    for name in files:
        if "/" in name or "\\" in name or name in {"tests", "node_modules"}:
            raise ValueError("Unexpected declared runtime path")
    return files


def checked_version(version: str) -> str:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError(f"Invalid package version: {version}")
    return version


def node_version(directory: Path) -> str:
    return checked_version(json.loads((directory / "package.json").read_text(encoding="utf-8"))["version"])


def astrbot_version(directory: Path) -> str:
    metadata = (directory / "metadata.yaml").read_text(encoding="utf-8")
    versions = re.findall(r"^version:\s*v?([^\s]+)\s*$", metadata, re.M)
    if len(versions) != 1:
        raise ValueError("Expected one AstrBot version")
    return checked_version(versions[0])


def archive(output: Path, entries: list[tuple[Path, str]]) -> dict:
    if output.exists():
        raise FileExistsError("Refusing to overwrite an existing publication archive: " + str(output))
    names = [name for _, name in entries]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate archive path")
    for path, name in entries:
        if path.is_symlink() or not path.is_file():
            raise ValueError("Regular allowlisted file required: " + str(path))
        if any(part in {"tests", "node_modules", "__pycache__"} for part in Path(name).parts):
            raise ValueError("Development payload cannot enter plugin archive")
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for path, name in entries:
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 4, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            target.writestr(info, path.read_bytes(), compresslevel=9)
    with zipfile.ZipFile(output) as target:
        if target.testzip() is not None or target.namelist() != names:
            raise ValueError("Archive structure check failed")
    return {"filename": output.name, "bytes": output.stat().st_size, "files": names,
            "source_archive": True, "tests_included": False, "license": "AGPL-3.0-only"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New publication asset directory")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    schema = json.loads((MODULES / "astrbot/_conf_schema.json").read_text(encoding="utf-8"))
    if any(schema[field]["default"] != [] for field in ("allowed_profile_ids", "allowed_api_principals")):
        raise ValueError("Distribution configuration must not include deployed identities")
    go = MODULES / "dsh-go"
    gofiles = declared(go / "package")
    entries = [(go / name, name) for name in OUTER_GO]
    entries += [(go / "package" / name, "package/" + name) for name in gofiles]
    reports = [archive(output / f"dsh-go-live2d-{node_version(go / 'package')}-agpl-source.zip", entries)]
    harness = MODULES / "dsh-harness"
    reports.append(archive(output / f"dsh-mobile-call-adapter-{node_version(harness)}-agpl-source.zip",
                           [(harness / name, name) for name in declared(harness)]))
    astrbot = MODULES / "astrbot"
    reports.append(archive(output / f"astrbot_plugin_live2d_call-{astrbot_version(astrbot)}-agpl-source.zip",
                           [(astrbot / name, name) for name in ASTRBOT]))
    report = {"repository": "https://github.com/Franka-official/DSH-L2D-Public",
              "archives": reports}
    (output / "plugin-source-assets.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
