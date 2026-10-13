# 第三阶段：共用单轮模型调用

基线：`ae34ac8`，`feature/liquid-glass-ui`。

普通聊天的 `GenerationHandler.generateInternal()` 和主动消息的
`generateWithTools()` 现在调用同一个 `generateModelTurn()`。公共层只负责一次
Provider 调用、流式/非流式 chunk 拼接和回调；不执行工具、不写数据库、不处理控制标记。

## 保留的入口行为

- 普通聊天继续按助手设置选择流式/非流式，保留日志、usage 合并、工具审批和输出转换。
- 主动消息继续流式生成，保留独立工具循环、12 轮工具预算和末尾模型回复机会。
- 主动消息先合并供 API 使用的历史，再建立只在本地使用的新 assistant 占位消息。
  公共层分别接收这两份消息，避免请求发送空占位消息或覆盖旧 assistant 的 ID。
- 主动消息继续按原策略处理 usage、PASS/WAIT/STOP/JUMP、思考收尾、失败清理与后台通知。
- 异常和取消直接交回入口处理，不转换成成功结果，不重试请求或工具。

## 验证

`GenerationModelTurnTest` 覆盖请求与本地状态分离、新消息 ID、思考和工具参数分片、
usage-only 尾帧、非流式、空流、API 失败和更新回调取消。

本地 Gradle 9.4.1 下载被网络阻断，未能运行 Android 编译和测试。
本分支启用原 APK 工作流，执行单元测试、Lint 与 APK 构建；结果以对应 Actions 为准。
真机仍需检查文字/图片、思考、工具后回复、停止、主动控制标记和历史完整性。

下一批才合并多轮模型/工具循环。当前 `generateWithTools()` 仍存在，第三阶段尚未完成。
