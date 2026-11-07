# DSH Go local bridge

[简体中文](README.md) · [Downloads](../../docs/DOWNLOADS.md) · [Compatibility and tests](../../docs/PLUGIN_COMPATIBILITY.en.md)

Connect DSH L2D to Go's conversations, models and tools on the same phone. The bridge carries text and local transcripts over `127.0.0.1`. Microphone input uses Go's ready local recognition service, and L2D plays the speech.

## Install and connect

Go’s on-device runtime uses Node.js 22.19 or later.

1. End the call. Extract the standalone plugin ZIP and open that directory in Go's terminal.

   ```sh
   node install.mjs --plan
   node install.mjs --install
   ```

2. Reload the Go engine when prompted. In Go settings, open DSH L2D Bridge (`DSH L2D桥接`) and select Enable or restore local bridge (`开启／恢复本机桥接`). Older versions label the entry `本机 DSH L2D`.
3. Follow Android's prompt to open L2D and approve the first connection. In L2D, select Local (`本机`) under Connection settings. Use the same Go entry to reconnect later.

First-time App Link verification can require internet access. An approved phone can restore the local connection offline. Model inference follows Go's provider configuration. The bridge uses app-level approval, separate from phone control and accessibility services.

## Update and maintain

The installer uses the `web` profile under `$DSH_HOME` and backs up the old plugin in `dsh-go/plugin-backups`. `node install.mjs --status` reports the installation on disk. The Go panel shows connection status. If installation returns `REGISTRATION_UNCONFIRMED`, confirm that the host has not registered the plugin before running `node install.mjs --install --retry-registration`.

Calls request `reasoningEffort: off` when the selected model declares support. A configuration rejection ends the wait and reports the error.

To uninstall, end the call, remove the plugin in Go, and reload the engine. L2D's Connection settings can stop the local service.

[Development checks](../../docs/PLUGIN_CHECKS.en.md) · [AGPL-3.0-only](COPYING) · [Third-party notices](THIRD_PARTY_NOTICES.md)
