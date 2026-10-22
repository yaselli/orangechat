# 版本号与应用内更新

本批代码将正式版升到 `2.3.0`，Debug 升到 `2.3.0-debug.1`，Android `versionCode` 均为 `165`。后续每次发包都递增 versionCode；正式版改 defaultConfig.versionName，Debug 同时调整 versionNameSuffix 与 VERSION_NAME 字段中的 debug 序号。

同一份源代码分别通过 Build Stable Release APK、Build Personal APK 构建。拍一拍、消息贴表情、记忆、MCP 等功能共用代码；两种包只在包名、既有签名配置及测试诊断展示上区分。两套应用的数据相互独立。

## 用户体验

- 启动聊天后，开启“更新提醒”的用户在有新版本时看到弹窗，可下载、稍后或忽略该版本。切换聊天窗口不会反复弹出；“稍后”在下次启动进程时可以再提醒。
- 设置 → 关于 → 检查更新可手动检查，不受“忽略此版本”影响。
- 自动检查失败时静默，手动检查失败时提示；成功结果在进程内缓存一小时，失败缓存两分钟，手动检查可立即重试。
- 正式版只接受正式 Release，Debug 只接受 `-debug.N` 的预发布版本；同时匹配 Android versionCode 和设备 ABI，不下载其他渠道或架构的包。
- 用户点击后使用系统下载管理器，下载完成从通知栏打开安装包。没有静默安装或强制更新。

旧版仍检查旧作者仓库，无法靠这次代码远程改变。第一次升级需手动安装新包，后续再通过新更新源提醒。

## 准备下一次更新

更新源固定为公开仓库 `yaselli/orangechat` 的 GitHub Releases，不需要在 App 中保存 GitHub 令牌。仅 Actions 附件、草稿或无匹配 APK 的 Release 不会产生更新提醒。

1. 从同一提交完成两条构建。构建脚本从 APK 实际包名、版本、debug 标记和 ABI 生成安装包文件名与 `release-metadata.json`，上传到既有 Actions 附件。
2. 以包含本次修改的分支运行 `release.yml`（Prepare Update Release），填写已成功的构建 run ID、`release` 或 `debug` 渠道，以及更新说明。工作流会核对构建来源、版本和 APK 校验和，再创建草稿。旧 master 中的旧版发布脚本不能用于这个新流程。
3. 在 GitHub Releases 检查草稿、安装包和更新说明后，手动发布。Debug 草稿自动标记 prerelease；正式版草稿不标记 prerelease。发布后 App 在下一次有效检查时提示更新。

可通过 CLI 指定工作流分支，例如：`gh workflow run release.yml --ref build/stable-release -f run_id=构建ID -f channel=release -f notes='更新说明'`。需先将这些修改合入目标分支；不在旧 master 上直接构建或发布。

文件名约定由脚本统一生成，例如 `Xiguaban-2.3.0-165-arm64-v8a-release.apk`。不要手工改掉版本、渠道或架构。草稿创建不会自动公开，也不覆盖已有 Release。

本次只实现和测试检查/下载/草稿准备流程，没有创建或公开新版 Release。完整安装仍需手机验证；签名必须沿用原渠道，否则 Android 无法覆盖安装。
