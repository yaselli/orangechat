# 第一阶段：安全与服务生命周期整改

基线：44ac3a463f0298d4855d1fa5bba16e5b8ae27a6e，Actions 34085013561 打包成功。

## 本批修改

- HTTP 请求日志仅保留 origin、方法、状态码、耗时和异常类型；不读取请求/响应正文，不保存头或 URL 路径/查询。
- 移除额外 HEADERS 拦截器；AI 调试缓存仅保存消息/工具数量。清除模型适配器、MCP、工具、OCR、插件配置与 QQ 消息路径中的自动正文/参数打印。
- 集中固定服务和错误通知 ID，修复音乐/Web 以及微信错误/QQ 服务通知冲突。
- 主动消息与每日 cron 使用服务作用域；取消后执行必要收尾。主动消息释放锁和停止服务在 Main 串行完成，避免旧任务停止新任务；每日 cron 合并运行中的重复启动。
- 主动消息、计时器先建立前台通知再处理恢复/旧入口；QQ、微信、音乐、Web、设备监听处理前台启动拒绝；dataSync 保活服务补 Android 15 onTimeout 停止回调。
- 内部主动消息、每日 cron、工作流开机 Receiver 改为非导出。系统受保护广播和同 UID PendingIntent 仍可投递；设备端需验证开机恢复。QUICKBOOT 厂商自定义广播若由非系统 UID 发送可能不再投递。
- feature/liquid-glass-ui 推送后自动运行 JVM 测试、Lint、打包；失败仍上传诊断报告，不放宽 Lint 门禁。

## 尚未完成 / 验证边界

- HTTP 策略待决定：当前保留原 usesCleartextTraffic=true。静态 XML 不能按用户运行时任意新增的公网域名/局域网地址自动建立例外，直接默认禁止会改变自定义 HTTP API、MCP、插件与本地连接兼容性。本批不宣称这项已修复。
- 尚不能宣称所有系统 logcat、第三方库日志或用户插件自行输出均已脱敏；本批针对自动请求、生成和工具链日志。发布前仍需用带标记的假密钥/聊天检查日志导出与 logcat。
- 本地 Gradle 9.4.1 下载被网络环境阻断，未在本地运行 Kotlin 编译、JUnit 或 Lint。XML/YAML 解析及 git diff --check 通过；实际门禁结果以本次提交的 Actions 为准。
- 六个新增 JVM 测试覆盖请求隐私、原异常传播、流式响应不被日志读取、IPv6 origin、固定通知 ID、内部 Receiver 策略。它们不替代真机服务生命周期/开机/锁屏测试。

## 真机回归

覆盖安装后验证普通聊天、主动消息锁屏触发、关闭功能后停止、重复启动、同时运行音乐/Web/机器人通知，以及重启后的定时恢复。
主动消息失败需区分未唤醒、接口失败与 AI 的 PASS；对照诊断 run ID。
Android 15+ 可按官方 foreground service timeout 文档在测试设备缩短 dataSync 配额，验证保活服务超时自行结束。

参考：https://developer.android.com/develop/background-work/services/fgs/timeout
网络配置：https://developer.android.com/privacy-and-security/security-config

后续设置写入、ChatService/Repository 架构重构不在本批范围。
