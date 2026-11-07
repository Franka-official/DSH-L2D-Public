# AstrBot mobile call plugin

[简体中文](README.md) · [Downloads](../../docs/DOWNLOADS.md) · [Compatibility and tests](../../docs/PLUGIN_COMPATIBILITY.en.md)

Use AstrBot's mobile WebChat from L2D. The plugin adds conversational phrasing and expression hints for the selected profile and identity, using the host's persona, model and tools.

## Install and pair

1. Upload the standalone ZIP in AstrBot's plugin manager. The package root contains `main.py` and `metadata.yaml`. The host range is `>=4.29.3,<4.30`. Runtime dependencies are `aiohttp` and `httpx`.
2. Create or select a dedicated mobile WebChat profile. Add its actual ID to `allowed_profile_ids`. The list is empty by default. Use a dedicated mobile profile ID other than `default`.
3. Enter the call relay URL and the phone's one-time code. The plugin creates a chat-only internal identity for this route. For an externally managed API identity, set its identifier in `allowed_api_principals`.
4. Clear the pairing code after setup. Credentials stay in the plugin's private directory.

[Connector setup](CONNECTOR.md) · [Supported scope](SUPPORTED.md) · [Development checks](../../docs/PLUGIN_CHECKS.en.md)

To uninstall, end the call, remove the plugin from the mobile WebChat profile, and uninstall it in the host. [AGPL-3.0-only](COPYING) · [Third-party notices](THIRD_PARTY_NOTICES.md)
