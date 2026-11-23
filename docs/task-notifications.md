# Android 任务通知

打开「设置 → 通知 → 任务通知」，允许系统通知。开关下方显示每个已启用桌面的注册状态。桌面需要支持 Android FCM 注册，通知网关需要配置相同 Firebase 项目的发送身份。旧桌面仍可连接和聊天，通知设置会提示更新。手机需要兼容的 Google Play 服务和访问 FCM 的网络。

桌面成功完成任务或有新的 Ask 等待回答时，网关发送高优先级 FCM 数据消息。Android 在后台校验绑定并使用「任务通知」渠道显示提醒。正文由本地资源生成。点击后会重新连接所属桌面并读取会话当前状态。应用在前台、系统权限关闭、渠道被禁用或桌面绑定已撤销时不会显示提醒。FCM 推送受手机网络、系统调度和用户在系统设置中强行停止应用等条件影响。

## 构建和发布

1. 在 Firebase 控制台选择与网关相同的项目，注册包名为 `dev.cpa.android` 的 Android 应用。
2. 下载该 Android 应用的 **客户端** `google-services.json`，放入本仓库的 `app/google-services.json`。该路径已被 Git 忽略。服务账号 JSON、私钥和发送权限只配置在网关。
3. 运行 `./gradlew :app:assembleDebug` 或仓库的验证脚本。只有文件存在时才应用 Google Services 插件；无此文件也可构建，设置中将显示“此版本未配置 Firebase”。
4. GitHub 发布工作流可读取仓库 Actions secret `FIREBASE_ANDROID_CONFIG`。值是客户端 JSON 全文，标签构建将其写入同一路径后生成两个 ABI 的 release APK。未配置时发布普通 APK。

## 注册与撤销

客户端使用 Firebase Messaging 26.0.2 的 FID 注册接口。用户开启后调用 `register()`，通过 `onRegistered(installationId)` 接收当前标识。桌面重连时重新注册；启用时回到前台会向 Firebase 刷新注册，撤销授权后会尝试向 Firebase 注销。两种传输均使用认证后的桌面连接。

| RPC | 参数 |
| --- | --- |
| `notification:registerClient` | 一个对象，包含安装级 `clientId`、绑定级 `connectionId`、`platform: "android"`、`provider: "fcm"`、保留原样的 FID `deviceToken` 和 `enabled: true` |
| `notification:unregisterClient` | 一个对象，包含同一 `clientId` 和绑定级 `connectionId` |

成功回执为 `{"ok":true}`。绑定级 ID 在重连和进程重启后稳定，手机本地将它映射到实际桌面连接。禁用或删除连接、更换桌面地址/隧道目标、关闭任务通知时，手机撤销该映射并清除对应通知。此后即使离线桌面仍保存旧注册，也无法触发显示或跳转。可再次连接的同一桌面会补发待处理的注销；已删除或被替换的离线桌面可能保留旧记录。

网关数据包含 `notificationType`（`ask` 或 `taskCompleted`）、绑定级 `connectionId` 和 `sessionId`。进入会话前再次检查绑定和连接启用状态。后台收件处理使用本地绑定与资源即可完成，无需唤醒桌面连接。

## 验证

`./scripts/verify.sh` 执行主机构建、JVM 回归、设备测试 APK 编译和 lint。`./gradlew :app:connectedDebugAndroidTest` 在设备上验证通知渠道、前后台过滤、绑定撤销和会话路由。真实投递需要在与网关配套的测试 Firebase 项目和带有 Google Play 服务的设备上验证，可分别在后台和冷启动下触发任务完成、Ask，并检查点击后所在的桌面与会话。
