# DeepSeek Harness 远程通话适配器

[English](README.en.md) · [下载](../../docs/DOWNLOADS.md) · [兼容与实测](../../docs/PLUGIN_COMPATIBILITY.md)

通过远程通话服务将 L2D 接入已有 DSH/DSHA 宿主。适配器在独立会话里转发助手最终正文，工具审批沿用宿主流程。

## 安装与配对

1. 解压独立插件 ZIP。按宿主的本地插件流程注册含 `package.json`、`plugin.js`、`cordis.patch.yml` 的目录。
2. 宿主使用 Node.js 22 或更新版本，并准备包声明的 peer 依赖和 `connection`、`sessionController`、`credentials` 服务。
3. 用现有 `relay_url` 和手机一次性连接码配对。宿主 App Key 与模型供应商 Key 分别配置，连接凭据保存在宿主的插件私有记录中。

源码 1.1.3 使用 DSH `v0.2.1-rc.4` 的会话接口。通话仅在模型声明支持时请求 `off` 档；配置拒绝会结束本轮等待。

[协议与鉴权](PROTOCOL.md) · [支持范围](SUPPORTED.md) · [开发检查](../../docs/PLUGIN_CHECKS.md)

卸载时先结束通话，再从宿主卸载插件。[AGPL-3.0-only](COPYING) · [第三方许可](THIRD_PARTY_NOTICES.md)
