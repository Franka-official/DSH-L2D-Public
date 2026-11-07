# 第三方代码与SDK许可边界

该表是来源索引，不代替各组件完整LICENSE、NOTICE或版本对应的许可文件。

| 组件 | 已核来源/许可 | 仓库处理 |
|---|---|---|
| MNN | `3b04761b4a8f0ab60dad259f94771f19d1264475`，Apache-2.0 | 保留许可、版权、补丁及修改说明；记录当前stream/cancel与sampler补丁，避免重新构建旧基线 |
| MOSS-TTS-Nano INT8 生成器、OpenMOSS 音频编解码器 | 上游模型卡声明 Apache-2.0；固定版本见[素材清单](ASSET_SOURCES.md) | 模型许可与角色音色提示分开记录。 |
| Audio8 0.1B ONNX INT8 | 模型卡声明 Apache-2.0，使用版本 `317c12d4e0da83847b594fcf8bd74bf2c76615ec` | 模型包保留来源与许可；派生增量解码图记录原图和权重来源。角色与参考音单独记录。 |
| ONNX Runtime | Microsoft，MIT | 随 Android 二进制保存对应版本许可与第三方通知。 |
| Qwen3-TTS Base（历史发行） | 官方0.6B Base模型卡声明Apache-2.0 | 保存上游模型/转换清单及完整许可；与参考WAV分开 |
| SoulLink | `02cd75ac0a085a87d84d1a885f1b33eca8742d53`，Apache-2.0 | 保留上游源、许可、构建输入和本项目adapter来源 |
| Cubism Framework / Core | Framework使用Live2D Open Software License；Core使用专有许可 | 自有代码附LICENSE中AGPL§7的特定Cubism链接许可；SDK原许可、再分发及出版条件继续有效，不重新授权Core |
| DeepSeek Harness | `dsh-v0.2.1-rc.4`，MIT | 复制部分保留原许可；独立插件记录实际宿主版本 |
| AstrBot / DSH Go | 已查宿主版本为AGPL-3.0 | 自写协议适配与实际复制的宿主代码分开；复制/改动的上游代码保留许可，不能自行添加第三方连接例外 |
| AndroidX、MediaPipe、其他实际打包依赖 | 以锁定构建的依赖与通知为准 | 汇总完整LICENSE/NOTICE，不能仅凭顶层库许可断言所有传递依赖已审完 |

边界规则：

- 单纯经独立协议/API调用另一应用，不自动证明整个客户端属于该应用的衍生代码；需按实际源码来源与组合方式判断。
- 将两个组件放到不同目录，并不自动解决同一二进制的许可冲突。
- LICENSE中的额外许可只覆盖有权授权的自有代码与Cubism Core/Framework链接组合；自有衍生代码仍受AGPL约束，第三方原许可不变。
- 不把修改过的宿主源码、参考测试摘录或SDK代码改标成“全部原创AGPL”。

原始依据：

- MNN：https://raw.githubusercontent.com/alibaba/MNN/3b04761b4a8f0ab60dad259f94771f19d1264475/LICENSE.txt
- Audio8 模型：https://huggingface.co/Edge0/audio8-TTS-0.1B-ONNX-INT8
- ONNX Runtime：https://github.com/microsoft/onnxruntime/blob/main/LICENSE
- Qwen模型（历史发行）：https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base/blob/5d83992436eae1d760afd27aff78a71d676296fc/README.md
- SoulLink：https://raw.githubusercontent.com/nanlingyin/soullink-emotion-sdk/02cd75ac0a085a87d84d1a885f1b33eca8742d53/LICENSE
- Cubism：https://github.com/Live2D/CubismNativeFramework/blob/develop/LICENSE.md
- Core：https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html
- DSH：https://raw.githubusercontent.com/deepseek-ai/deepseek-harness/dsh-v0.2.1-rc.4/LICENSE
- Go AGPL原文：https://raw.githubusercontent.com/ccch1mneyyy/dsh-go/b4f2d24055e28441830185a7253fd6bc18d9e7f3/LICENSE
