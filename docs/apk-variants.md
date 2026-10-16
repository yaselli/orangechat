# 正式版与记忆测试版

| 用途 | Gradle 构建 | 包名 | 签名 |
| --- | --- | --- | --- |
| 正式版 | `assembleRelease` | `me.rerere.orangechat` | 独立 release 密钥 |
| 现有个人 debug 版 | `assembleDebug` | `me.rerere.orangechat.debug` | 保持原配置 |
| 记忆测试版 | `assembleMemoryTest` | `me.rerere.orangechat.memorytest` | 固定共享 debug 密钥 |

三个包可以同时安装，应用私有数据库、设置和附件目录各自独立。记忆测试版显示为「西瓜瓣·记忆测试」。
这是构建隔离准备，尚未实现新记忆系统。正式版保持当前功能；记忆开发在 `feature/memory-system` 进行。

## 自动打包

`Build Stable or Memory Test APK` 工作流：

- 推送 `build/stable-release`：验证并生成正式 APK。
- 推送 `feature/memory-system`：验证并生成记忆测试 APK。
- 手动运行可选择构建类型，注意选择包含所需代码的分支。
- 只上传 Actions 附件，不创建公开 Release；普通手机选择 arm64-v8a APK。
- 正式版复用既有 `release.yml` 的 `KEY_BASE64`、`SIGNING_CONFIG` secrets。后者的 storeFile 应指向 app 模块下的 `app.key`。缺少签名时明确失败，不生成临时密钥，不回退到 debug 签名。
- 测试版复用 `ANDROID_DEBUG_KEYSTORE_BASE64`，保证后续测试包能覆盖更新。
- 上传前校验实际 APK 包名及 debuggable 标志。

## 数据使用

正式版首次安装不会自动读取现有 debug 版的数据。保留原应用，在原应用导出备份，再按需在新应用导入；确认成功后再自行决定是否保留旧应用。

记忆测试使用备份的副本，不共享正式版的数据库或附件目录。导入兼容与升级迁移仍需单独验证；本配置不承诺旧版本可以读取新版本备份。不要为了换版本先卸载现有应用或清除数据。
