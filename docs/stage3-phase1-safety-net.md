# 第三阶段第 1 批：生成流程安全网

基线：`da05dc6`（`feature/liquid-glass-ui`）。本批只盘点生成入口、提取可独立测试的纯逻辑并建立真机清单，不统一上下文，不迁移主动消息生成循环。

## 代码地图

| 入口 | 当前路径 | 保存与展示 | 特殊行为 |
|---|---|---|---|
| 普通聊天 | `ChatVM -> ChatService.sendMessage -> handleMessageComplete -> GenerationHandler.generateText` | `ChatService` 更新 session 并持久化 | 工具审批、取消、标题、建议、通知 |
| Web 前端 | `ConversationRoutes -> ChatService.sendMessage` | 与普通聊天共用 | 等待同一生成完成事件 |
| QQ 机器人 | `QqBotService -> ChatService.sendMessage` | 与普通聊天共用后转发 | 平台消息收发 |
| 微信机器人 | `WeixinBotService -> ChatService.sendMessage` | 与普通聊天共用后转发 | 平台消息收发 |
| 主动消息 | `ProactiveMessageTriggerService -> generateWithTools` | 自行流式更新，再经 `ChatService` 的会话锁保存 | 闲置判断、`PASS/WAIT/STOP`、后台通知、查岗许可 |
| 语音拒接回复 | `ChatService.notifyVoiceCallDeclined` 直接调用 Provider | 作为独立 assistant 消息追加 | 轻量单轮生成，不走工具循环 |

普通聊天、Web、QQ 和微信已经共用 `GenerationHandler`。第三阶段主要重复点在主动消息的 `generateWithTools()`；语音拒接是后续小入口，不在第一刀中改动。

## 已有自动保护

- `ProactiveMessageStateTest`：`PASS/WAIT/STOP/JUMP` 解析及思考文本不误触发。
- `ProactiveMessageOwnershipTest`：主动消息必须使用新消息 ID，停止清理不能删除旧回复。
- Provider 消息测试：OpenAI、Claude、Google 的工具消息格式和多轮顺序。
- `ToolApprovalStateTest`：批准、拒绝、回答可恢复；自动和等待状态不可恢复。
- 额外注入、时间提醒、主动消息错误分类等独立逻辑测试。

## 本批新增保护

- 悬空且不可恢复的工具调用会从主动消息请求历史中移除，不删除前后的正常聊天。
- 已执行失败的工具结果仍保留原 `toolCallId`。
- 批准、拒绝和已回答的工具保持可恢复，不会被历史清理误删。
- 相邻同角色消息合并时保留第一条消息 ID。
- OpenAI 兼容格式中，成功、失败和拒绝的并行工具调用都必须生成一条 ID 完全对应的工具结果。

## 快速真机清单

- [ ] 普通文字消息能收到一条正常回复。
- [ ] thinking 模型同时显示思考和正文，完成后不一直显示“思考中”。
- [ ] 工具成功后继续生成最终回复。
- [ ] 工具失败或拒绝后不出现 `insufficient tool messages`。
- [ ] 生成中点停止后，可以立即发送下一条消息。
- [ ] 退出并重新进入对话，历史没有减少、重复或覆盖。

## 主动消息慢速清单

仅在主动消息相关代码改变时运行：

- [ ] 休眠到点后能在后台触发并显示通知。
- [ ] `[PASS]` 不显示消息，并保留后续调度。
- [ ] `[WAIT:分钟]` 不显示消息，并按新时间等待。
- [ ] `[STOP]` 停止后续触发，直到用户再次发言。
- [ ] 主动消息不覆盖上一轮 AI 回复。
- [ ] 主动消息接口或工具失败后不吞历史。
- [ ] 主动消息中的普通工具可执行；查应用使用情况仍受单独许可控制。

## 第一批边界

- 不建立假模型、假数据库或完整流式测试框架。
- 不迁移 `generateWithTools()`。
- 不改变提示词、调度、工具权限或消息保存规则。
- 不混入日历、UI、OCR、长截图或其他功能修改。
- 本地环境无法下载 Gradle 9.4.1；编译、JUnit、Lint 与 APK 结果以 GitHub Actions 为准。
