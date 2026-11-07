# 许可证状态

状态：**项目自有代码采用 GNU AGPL 第3版，仅第3版（AGPL-3.0-only）。第三方组合二进制边界仍需单独审核。**

许可范围见 [LICENSE](LICENSE)，完整、不改写的许可正文见 [COPYING](COPYING)。本文件解释发行边界，不替第三方权利人授权。贡献者保留各自版权，原有第三方许可和署名继续有效。

## 自有代码范围

覆盖项目自行编写且有权授权的 Android Kotlin/JNI胶水、协议定义、后端适配逻辑、插件和构建工具。代码全部公开与要求下游公开修改是两件事；本项目选择后者，但范围仍受实际版权来源、许可正文及第三方原有许可约束。下游对 covered work 的分发和网络交互义务应阅读完整原文，不能简化为“所有独立软件一律开源”。

## 不被重新授权的内容

- 第三方SDK、源代码、库和其许可证，继续使用原许可与归属。
- 模型权重、参考音频、角色模型/纹理/动作、图标与图片，按各自权利和来源记录处理。
- 签名私钥、账号凭据、运行时配对秘密、聊天记录与私人诊断资料，不属于拟发布源代码。

## 公开前仍需完成

1. 以正式发布构建对应的文件清单确定哪些内容确为项目自有、哪些来自上游。
2. 根LICENSE已授予AGPL§7下仅针对自有代码与Cubism Core/Framework组合的链接额外许可；它不代表已替第三方取得SDK授权。
3. 该许可只覆盖相应权利人有权授权的自有代码；不能借此放宽第三方原有许可，也不能替上游AGPL代码添加例外。
4. 上游代码与摘录保留其原始许可、版权与修改说明。通过独立协议调用另一应用，不自动等同于复制该应用代码；反过来，分目录存放也不能消除实际复制或组合的许可义务。
5. 独立插件 ZIP 需携带适用许可与通知；仓库根文件不会自动进入旧冻结包。自有贡献按AGPL-3.0-only及LICENSE中特定Cubism链接许可授权，并按DCO签署。

The project-owned source is licensed under AGPL-3.0-only with the specific Cubism Core/Framework linking permission in LICENSE. Third-party code and media retain their own terms. This does not certify compliance with each SDK redistribution or publication condition. See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) and [ASSET_SOURCES.md](ASSET_SOURCES.md).
