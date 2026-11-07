# DSH Go 本机桥接

[English](README.en.md) · [下载](../../docs/DOWNLOADS.md) · [兼容与实测](../../docs/PLUGIN_COMPATIBILITY.md)

让同一部手机上的 DSH L2D 使用 Go 的会话、模型和工具。桥接通过 `127.0.0.1` 传输文字与本地转录。麦克风输入复用 Go 已就绪的本地识别，语音由 L2D 播放。

## 安装与连接

Go 本机运行环境使用 Node.js 22.19 或更新版本。

1. 结束通话，在 Go 终端进入独立插件 ZIP 的解压目录。

   ```sh
   node install.mjs --plan
   node install.mjs --install
   ```

2. 按安装回执重载 Go 引擎。在 Go 的「设置」打开「DSH L2D桥接」，点「开启／恢复本机桥接」。旧版入口为「本机 DSH L2D」。
3. 首次连接按系统提示打开 L2D 并授权，然后在 L2D 的「连接管理」选择「本机」。后续可在同一入口恢复。

首次 App Link 验证可能需要联网。已授权设备的本机连接可在离线时恢复，模型推理仍使用宿主所选供应商。桥接使用应用授权，与手机操控和无障碍服务分开。

## 更新与维护

安装器使用 `$DSH_HOME` 下的 `web` profile，旧插件备份位于 `dsh-go/plugin-backups`。运行 `node install.mjs --status` 查看磁盘安装状态，连接状态见 Go 面板。收到 `REGISTRATION_UNCONFIRMED` 后，确认宿主尚未注册再用 `node install.mjs --install --retry-registration`。

通话仅在所选模型声明支持时请求 `reasoningEffort: off`。代理拒绝配置时结束等待并显示错误。

卸载时先结束通话，再从 Go 插件管理卸载并重载引擎。L2D 的「连接管理」可停止本机服务。

[开发检查](../../docs/PLUGIN_CHECKS.md) · [AGPL-3.0-only](COPYING) · [第三方许可](THIRD_PARTY_NOTICES.md)
