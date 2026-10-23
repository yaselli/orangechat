# 版本号与应用内更新

当前正式版为 `2.3.1`，Debug 为 `2.3.1-debug.1`，Android `versionCode` 均为 `166`。`2.3.0 / 165` 是新更新源的首个版本。后续每次发包都递增 versionCode；正式版改 defaultConfig.versionName，同一基础版本内追加 Debug 更新时，同时调整 versionNameSuffix 与 VERSION_NAME 字段中的 debug 序号。

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
2. 将已通过验证的功能 PR 合入 `build/stable-release` 后，仅从该正式版分支运行 `release.yml`（Prepare Update Release），填写已成功的构建 run ID、`release` 或 `debug` 渠道，以及更新说明。可以复用已成功、且源提交已随 PR 合入维护分支的构建附件；应核对合并前后源树一致。工作流会核对构建来源、版本和 APK 校验和，再创建草稿。不要使用旧 `master` 的发布脚本；无需向 `master` 合入任何修改。
3. 在 GitHub Releases 检查草稿、安装包和更新说明后，手动发布。Debug 草稿自动标记 prerelease；正式版草稿不标记 prerelease。发布后 App 在下一次有效检查时提示更新。

通过 CLI 明确指定正式版分支：`gh workflow run release.yml --ref build/stable-release -f run_id=构建ID -f channel=release -f notes='更新说明'`。工作流增加分支校验，仅允许在 `build/stable-release` 执行。正式版分支今后每次更新会同时触发 Release 和 Debug 构建。

文件名约定由脚本统一生成，例如 `Xiguaban-2.3.1-166-arm64-v8a-release.apk`。不要手工改掉版本、渠道或架构。草稿创建不会自动公开，也不覆盖已有 Release。

发布完成与否以 GitHub Releases 的公开状态为准。完整安装仍需手机验证；签名必须沿用原渠道，否则 Android 无法覆盖安装。
