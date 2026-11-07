# 插件开发检查

[English](PLUGIN_CHECKS.en.md) · [兼容与实测](PLUGIN_COMPATIBILITY.md)

## 运行检查

使用 Node.js 22 或 24，以及 Python 3.11 或更新版本。

```sh
python -m pip install -r requirements-plugins-dev.txt
python tools/check_plugins.py
```

可用 `--node /absolute/path/to/node` 指定 Node，或用 `--syntax-only` 只做语法检查。CI 使用 [插件工作流](../.github/workflows/plugins.yml)。

| 模块 | 检查范围 |
| --- | --- |
| DSH Go | 安装、升级、冲突处理 |
| DeepSeek Harness | 配对、会话、路由、SSE 和取消 |
| AstrBot | 作用域、身份、HTTP 对话和取消 |

开发测试使用临时目录、宿主替身和模拟 HTTP。设备与宿主实测见[兼容表](PLUGIN_COMPATIBILITY.md)。

## 打包

```sh
python tools/package_plugins.py --output /path/to/new-output-directory
```

各插件生成独立源码 ZIP。输出目录同时包含 `plugin-source-assets.json`，记录文件名和大小。
