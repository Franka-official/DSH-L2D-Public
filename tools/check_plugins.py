"""Run plugin syntax checks and offline tests."""
from __future__ import annotations

import argparse
import ast
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MODULES = ROOT / "integrations"


def run(args: list[str], env: dict[str, str]) -> None:
    subprocess.run(args, cwd=ROOT, env=env, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", default="node", help="Node.js 22 or 24 executable; default: node on PATH")
    parser.add_argument("--syntax-only", action="store_true", help="Check syntax only")
    args = parser.parse_args()
    node = shutil.which(args.node)
    if not node:
        parser.error("Node.js 22 or 24 is required")
    version = subprocess.check_output([node, "--version"], text=True).strip()
    if not version.startswith(("v22.", "v24.")):
        parser.error("Expected Node.js 22 or 24, found " + version)
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    javascript = sorted(p for p in MODULES.rglob("*") if p.suffix in {".js", ".mjs"})
    python = sorted(MODULES.rglob("*.py"))
    for path in javascript:
        run([node, "--check", str(path)], env)
    for path in python:
        ast.parse(path.read_text(encoding="utf-8"), filename=str(path.relative_to(ROOT)))
    schema = json.loads((MODULES / "astrbot/_conf_schema.json").read_text(encoding="utf-8"))
    for field in ("allowed_profile_ids", "allowed_api_principals"):
        if schema[field]["default"] != []:
            raise ValueError("Distribution defaults must not contain a deployed identity: " + field)
    if not args.syntax_only:
        try:
            installed = importlib.metadata.version("httpx")
        except importlib.metadata.PackageNotFoundError:
            parser.error("Install development requirements first: python -m pip install -r requirements-plugins-dev.txt")
        if installed != "0.28.1":
            parser.error("The checked development dependency is httpx==0.28.1; found " + installed)
        test_files = sorted(path for name in ("dsh-go", "dsh-harness")
                            for path in (MODULES / name / "tests").glob("*.test.mjs"))
        run([node, "--test", *(str(path.relative_to(ROOT)) for path in test_files)], env)
        run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "integrations/astrbot/tests",
             "-p", "test_*.py", "-v"], env)
    print(json.dumps({"scope": "portable-plugin-checks", "node": version,
                      "python": sys.version.split()[0], "javascript_syntax_files": len(javascript),
                      "python_syntax_files": len(python), "syntax_passed": True,
                      "offline_unit_tests_passed": not args.syntax_only,
                      "test_mode": "not-run" if args.syntax_only else "temporary-files-and-mock-hosts",
                      "host_sdk_integration_tested": False, "android_build_tested": False,
                      "device_tested": False, "model_inference_tested": False}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
