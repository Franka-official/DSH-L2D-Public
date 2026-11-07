# Plugin development checks

[简体中文](PLUGIN_CHECKS.md) · [Compatibility and device tests](PLUGIN_COMPATIBILITY.en.md)

## Run checks

Use Node.js 22 or 24 and Python 3.11 or later.

```sh
python -m pip install -r requirements-plugins-dev.txt
python tools/check_plugins.py
```

Use `--node /absolute/path/to/node` to select a Node executable or `--syntax-only` to check syntax. CI runs the [plugin workflow](../.github/workflows/plugins.yml).

| Module | Coverage |
| --- | --- |
| DSH Go | Installation, upgrades, and conflicts |
| DeepSeek Harness | Pairing, sessions, routing, SSE and cancellation |
| AstrBot | Scope, identity, HTTP chat and cancellation |

Development tests use temporary directories, host doubles and mock HTTP. See the [compatibility table](PLUGIN_COMPATIBILITY.en.md) for device and host tests.

## Build packages

```sh
python tools/package_plugins.py --output /path/to/new-output-directory
```

Each plugin gets its own source ZIP. The output also includes `plugin-source-assets.json`, which records artifact names and sizes.
