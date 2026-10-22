# 正式版与 Debug 版

| 用途 | 构建 | 包名 |
| --- | --- | --- |
| 当前稳定功能，日常使用 | `assembleRelease` | `me.rerere.orangechat` |
| 功能验证，测试使用 | `assembleDebug` | `me.rerere.orangechat.debug` |

继续使用现有 release/debug 两种构建，不新增记忆专用包。两者可同时安装，应用私有数据库、设置和附件目录独立。新的 debug 会更新手机上原有的 debug 应用，因此在开始记忆测试前，先将现有数据备份并导入正式版，确认可用。

## 构建流程

- `build/stable-release`：Build Stable Release APK 工作流执行测试、lint、release 打包，校验真实包名与 debuggable 标志，仅上传 Actions 附件，不创建公开 Release。
- Debug 使用既有 Build Personal APK 工作流；本次修复分支同时触发正式版与 Debug 构建，确保来自同一提交。
- 正式版复用既有 `release.yml` 的 `KEY_BASE64`、`SIGNING_CONFIG` secrets；storeFile 应指向 app 模块下的 `app.key`。缺少签名会明确失败，不生成临时密钥，不回退到 debug 签名。
- Debug 保持原签名配置和 `ANDROID_DEBUG_KEYSTORE_BASE64`。
- 普通手机选择 arm64-v8a APK。

正式版首次安装不会自动读取现有 debug 数据。保留原应用，在原应用导出备份，再在正式版导入并确认成功。按需记忆召回与来源校验已合入正式版；测试时可在 debug 中使用备份副本。不要在迁移确认前卸载原应用或清除数据。

“请求体积”和“记忆引用”是仅供 Debug 版使用的诊断入口。正式版隐藏两个入口及记忆引用显示开关，也不收集请求体积；普通 token 统计和实际记忆召回不受影响。

版本号、更新弹窗和发布草稿流程见 [应用内更新](app-updates.md)。
