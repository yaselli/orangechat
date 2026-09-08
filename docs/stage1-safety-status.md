# 第一阶段：安全与服务生命周期整改

基线：44ac3a463f0298d4855d1fa5bba16e5b8ae27a6e，Actions 34085013561 打包成功。

## 本批修改

- HTTP 请求日志仅保留 origin、方法、状态码、耗时和异常类型；不读取请求/响应正文，不保存头或 URL 路径/查询。
- 移除额外 HEADERS 拦截器；AI 调试缓存仅保存消息/工具数量。清除模型适配器、MCP、工具、OCR、语音、文件错误、登录二维码、插件配置与 QQ 消息路径中的自动正文/参数打印。
- 集中固定服务和错误通知 ID，修复音乐/Web 以及微信错误/QQ 服务通知冲突。
- 主动消息与每日 cron 使用服务作用域；取消后执行必要收尾。主动消息释放锁和停止服务在 Main 串行完成，避免旧任务停止新任务；每日 cron 合并运行中的重复启动。
- 主动消息、计时器先建立前台通知再处理恢复/旧入口；QQ、微信、音乐、Web、设备监听处理前台启动拒绝；dataSync 保活服务补 Android 15 onTimeout 停止回调。
- 内部主动消息、每日 cron、工作流开机 Receiver 改为非导出。系统受保护广播和同 UID PendingIntent 仍可投递；设备端需验证开机恢复。QUICKBOOT 厂商自定义广播若由非系统 UID 发送可能不再投递。
- feature/liquid-glass-ui 推送后自动运行 JVM 测试、Lint、打包；失败仍上传诊断报告，不放宽 Lint 门禁。

## 尚未完成 / 验证边界

- HTTP 策略待决定：当前保留原 usesCleartextTraffic=true。静态 XML 不能按用户运行时任意新增的公网域名/局域网地址自动建立例外，直接默认禁止会改变自定义 HTTP API、MCP、插件与本地连接兼容性。本批不宣称这项已修复。
- 尚不能宣称所有系统 logcat、第三方库日志或用户插件自行输出均已脱敏；本批针对自动请求、生成和工具链日志。发布前仍需用带标记的假密钥/聊天检查日志导出与 logcat。
- 本地 Gradle 9.4.1 下载被网络环境阻断，未在本地运行 Kotlin 编译、JUnit 或 Lint。XML/YAML 解析及 git diff --check 通过；实际门禁结果以本次提交的 Actions 为准。
- 八个新增 JVM 测试覆盖请求隐私、原异常传播、流式响应不被日志读取、IPv6 origin、固定通知 ID、内部 Receiver 策略、主动消息异常分类不回显正文。它们不替代真机服务生命周期/开机/锁屏测试。

## 真机回归

覆盖安装后验证普通聊天、主动消息锁屏触发、关闭功能后停止、重复启动、同时运行音乐/Web/机器人通知，以及重启后的定时恢复。
主动消息失败需区分未唤醒、接口失败与 AI 的 PASS；对照诊断 run ID。
Android 15+ 可按官方 foreground service timeout 文档在测试设备缩短 dataSync 配额，验证保活服务超时自行结束。

参考：https://developer.android.com/develop/background-work/services/fgs/timeout
网络配置：https://developer.android.com/privacy-and-security/security-config

后续设置写入、ChatService/Repository 架构重构不在本批范围。

## 首次门禁结果与历史基线

运行 34107913827：测试在 highlight 的示例测试编译时发现 JUnit 依赖缺失；search 存在同样配置遗漏，本批补齐，不删除测试。
Lint 报 169 个错误：119 个缺失翻译、39 个 Compose 资源读取、5 个 locale 观察问题，以及返回手势、Context 转换、Flow 和可选电话硬件声明等问题。

本批补充可选电话硬件声明，消除 Manifest 的一个错误。其余 168 个错误记录在 app/lint-baseline.xml；每个报错位置对应的完整源文件 blob 均与 44ac3a4 完全相同。这份清单来自首次实际 Lint 报告，保留问题 ID、消息、源行和位置；不包含本批新增问题，也不关闭任何规则。不自动刷新基线，未来新增错误仍阻止构建，403 个警告仍显示。
这些历史 UI/翻译问题没有被修好，后续在对应模块阶段处理并从基线删除；不能把门禁通过解释为仓库零问题。

第二轮 34108884644：Lint 门禁通过（168 条既有错误按基线列账，403 条警告仍显示）；ai 98 项测试中 13 项因旧反射方法签名失败，app 106 项中分享模型列表旧断言和通知 ID 编译器字段过滤失败。修正测试的参数签名和既有行为契约，不改变生产聊天行为；思考历史保留与分享时省略模型列表在 44ac3a4 已存在。通知 ID 检查仅枚举业务命名常量，排除 Compose 自动生成的 $stable 字段。

## 历史 Lint 清单收尾（2026-09-08）

49 条代码问题改用 LocalResources、LocalConfiguration、LocalActivity、OnBackPressedDispatcher，通话页空状态流改为 remember 持有。返回锁定页仍回桌面且不解锁；生物识别返回仍报告用户取消。
119 条缺失翻译补入日语、韩语、俄语与缺失中文资源；品牌和 URL 保持原值，重复协议通过资源引用复用同一正文。对照原文校验资源键、重复键及格式占位符；译文仍欢迎母语使用者校对。已有繁体资源保留，新增中文项提供繁体版本，其余繁体缺项沿用 Android 原有中文资源回退。
历史 baseline 条目已全部移除，等待本次 CI 验证后才可确认这 168 条均已消除；不关闭规则，警告另计。HTTP 策略继续单独处理。
