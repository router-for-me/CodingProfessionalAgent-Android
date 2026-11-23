# CodingProfessionalAgent for Android

CPA 的原生 Android 客户端。用手机连接 CPA 桌面端，查看项目与会话，随时跟进桌面 Agent 的任务。Agent 仍在桌面端运行。

**[下载 APK](https://github.com/router-for-me/CodingProfessionalAgent-Android/releases/latest)** · Android 13 及以上

## 快速开始

1. 安装与设备架构匹配的 APK。大多数现代手机选 `arm64-v8a`，32 位 ARM 设备选 `armeabi-v7a`。
2. 在 CPA 桌面端开启 Web 服务。
3. 在 Android 应用中打开「设置 → 添加连接」。手机能访问桌面时选直连；已配置共享网关时可选隧道。地址填写、权限和网络安全说明见[连接指南](docs/connection.md)。

## 主要功能

- 管理项目和会话，发送、排队、引导、停止或恢复任务，也可以分叉会话。
- 编辑消息，添加附件、引用技能，处理 Ask 交互并查看子会话。
- 查看 Git 差异和文件内容（只读）。

暂不支持远程推送通知；尚未确认真实模型能否识别通过 Android 发送的图片附件。详见[功能矩阵](docs/migration/current-feature-matrix.md)。

## 开发

按[开发环境说明](docs/migration/environment.md)配置 JDK 和 Android SDK，然后运行：

```sh
./scripts/verify.sh
```

脚本构建调试 APK、运行 JVM 单测、编译设备测试 APK 并执行 lint。调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。

[开发指南](AGENTS.md) · [架构](docs/architecture.md) · [协议](docs/protocol.md) · [视觉基线](docs/ui-parity.md) · [发布流程](docs/releasing.md) · [MIT 许可证](LICENSE)
