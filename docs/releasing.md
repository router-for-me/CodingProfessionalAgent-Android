# Android 发布流程

日常开发在 `dev`。提交好发布改动后，先同步本地 `main`，再合并本地 `dev` 并推送 `main`：

```sh
git switch main &&
  git pull --ff-only origin main &&
  git merge dev &&
  git -c push.followTags=false push origin main
```

推送 `dev` 或 `main` 会运行 `Android checks`（仓库检查、debug 构建、JVM 测试、设备测试 APK 编译、lint）。确认刚推送的 `main` 提交通过检查后，再为该提交打发布标签。

## 签名配置

首次发布前，在 GitHub **Settings → Environments → release** 设置以下 environment secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布 keystore 文件的 Base64 编码（不是文件路径） |
| `ANDROID_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 发布签名别名 |
| `ANDROID_KEY_PASSWORD` | 该私钥的密码 |

妥善备份并持续使用同一发布密钥，否则安装用户无法覆盖升级。限制 `release` 环境只允许 `v*` 标签部署，并用 tag ruleset 限制这些标签的创建、修改和删除权限。

## 打标签并验证产物

`main` 的检查通过后，确认本地 `main` 指向该次通过检查的提交，再创建并推送单个新标签，例如：

```sh
git tag -a v1.2.3 -m 'Release v1.2.3' main &&
  git -c push.followTags=false push origin refs/tags/v1.2.3
```

[Android CI](../.github/workflows/android.yml) 只接受 `v主.次.补丁` 格式的发布标签，且对应提交必须已进入 `main`。检查通过后构建 release APK（`versionName` 取标签去掉 `v`，`versionCode` 取工作流运行序号加一），签名、验签后创建 GitHub Release，附上 ARM32（`CPA-Android-v1.2.3-armeabi-v7a.apk`）、ARM64（`CPA-Android-v1.2.3-arm64-v8a.apk`）、两份最终签名 APK 的哈希清单 `SHA256SUMS.txt` 及自动生成的发布说明。将三个附件下载到同一目录，运行 `shasum -a 256 -c SHA256SUMS.txt`（macOS）或 `sha256sum -c SHA256SUMS.txt`（Linux），两个文件都应显示 `OK`。缺少签名配置不会回退到 debug 签名。若日后更换工作流导致运行序号重置，应确保新 `versionCode` 大于历史发布值。

带任务通知的 APK 需要在标签构建前配置仓库 Actions secret `FIREBASE_ANDROID_CONFIG`，见[任务通知配置](task-notifications.md#构建和发布)。
