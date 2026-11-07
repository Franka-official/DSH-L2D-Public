# DeepSeek Harness remote call adapter

[简体中文](README.md) · [Downloads](../../docs/DOWNLOADS.md) · [Compatibility and tests](../../docs/PLUGIN_COMPATIBILITY.en.md)

Connect L2D to an existing DSH/DSHA host through a remote call service. The adapter forwards the final assistant reply from a dedicated session. Tool approval stays with the host.

## Install and pair

1. Extract the standalone plugin ZIP. Use the host's local plugin installer to register the directory containing `package.json`, `plugin.js` and `cordis.patch.yml`.
2. Use Node.js 22 or later and provide the declared peer dependencies and the `connection`, `sessionController` and `credentials` host services.
3. Pair using the existing `relay_url` and the phone's one-time code. Configure the host App Key separately from the model provider key. Pairing credentials stay in the host's private plugin storage.

Source version 1.1.3 uses the DSH `v0.2.1-rc.4` session interface. Calls request `off` when the model declares support. A rejected configuration ends the wait for that turn.

[Protocol and authentication](PROTOCOL.md) · [Supported scope](SUPPORTED.md) · [Development checks](../../docs/PLUGIN_CHECKS.en.md)

End the call before removing the adapter through the host. [AGPL-3.0-only](COPYING) · [Third-party notices](THIRD_PARTY_NOTICES.md)
