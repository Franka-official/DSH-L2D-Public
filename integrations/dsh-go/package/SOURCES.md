# 固定版本接口依据

- DSH Go1.0.10固定提交b4f2d24055e28441830185a7253fd6bc18d9e7f3：
  - https://github.com/ccch1mneyyy/dsh-go/blob/b4f2d24055e28441830185a7253fd6bc18d9e7f3/docs/design/plugin-seams.md
  - https://github.com/ccch1mneyyy/dsh-go/blob/b4f2d24055e28441830185a7253fd6bc18d9e7f3/app/lib/ui/plugins/islands/island_messages.dart
  - https://github.com/ccch1mneyyy/dsh-go/blob/b4f2d24055e28441830185a7253fd6bc18d9e7f3/app/lib/ui/plugins/islands/island_view.dart
- DSH固定dsh-v0.2.1-rc.4：
  - https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.1-rc.4/packages/util/home-paths/src/index.ts
  - https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.1-rc.4/packages/boot/app-boot/src/profile.ts
  - https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.1-rc.4/apps/cli/src/plugin.ts
  - https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.1-rc.4/packages/boot/plugin-manager/src/operations.ts
  - https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.1-rc.4/packages/experimental/api-speech-to-text/src/index.ts

[VERIFIED][HIGH] 运行层仅使用Node内置模块、包内模块与宿主提供的React/DSH服务；不捆绑官方源码、参考目录或测试。当前HTTPS普通外链不能发送custom scheme或指定Android组件；不使用PhoneControl绕过这一边界。

## 通话范围的模型控制

`adapter.mjs` 与 `call-policy.mjs` 和 `integrations/dsh-harness` 对应文件保持相同源码。
只对本适配器创建且 RPC ID 匹配的通话步骤，通过官方 Agent scoped `agent/pre-step`、
`agent/request`（prepend + after-next）检查模型能力；明确声明支持 `off` 才覆盖该档位，否则保留原请求。
不调用会保存部署默认的 `selectModel`。请求拒绝通过精确 turn 终态或 Agent scoped `agent/error` 结束等待，
不会自动重发、降档或给正常长请求增加总时限。
范围与生命周期依据：
- https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.1-rc.4/packages/core/agent/src/runtime-types.ts
- https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.1-rc.4/vendor/cordis/src/events.ts
- https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.1-rc.4/packages/llm/llm-pi-ai/src/adapter.ts

`off` 是 Harness effort，不保证任意第三方网关均明确关闭推理。实际 wire 字段取决于模型适配声明，
思考字符观测不能替代服务端参数验收。可选 `call_options` / `call_metrics` 只经已有本机授权通道、
在手机 discover 声明 `call_diagnostics: live2d.call-options.v1` 后发送，不存正文或凭据。
