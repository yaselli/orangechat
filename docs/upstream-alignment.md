# 上游补全清单

对照日期：2026-10-23。维护基线：`build/stable-release` 的 `2f9f63ce`。
上游基线：RikkaHub 3.1.0，以及开发提交 `43f1a5df24adc09b6720ae2e8aa568a12a5bf43a`。
两边版本号独立；本清单按代码和功能对照，不代表落后多少个版本或提交。

当前工作分支：`feature/upstream-usage-dashboard`。所有 PR 以 `build/stable-release` 为目标。
每批先适配、测试，再单独合并；不向本仓库 `master` 合并。

第一批实现与最新验证结果：[PR #19](https://github.com/yaselli/orangechat/pull/19)。
2026-10-23：第一批及用量累计基础（PR #20）通过 Debug／Release 构建，用户安装同源测试包反馈正常，已合入维护分支。

## 第一批：兼容性和搜索性能

| 项目 | 状态 | 验收重点 |
| --- | --- | --- |
| Responses API 识别服务端函数执行结果 | 已适配，验证见 PR | 流式／非流式结果按 call_id 关联；区分 item.id；不重复输入或输出；空结果仍代表已执行；手机工具仍能正常待执行 |
| OpenAI 兼容中转的空值响应 | 已适配，验证见 PR | 跳过非对象 SSE 心跳和空引用；保留多行完整 JSON；真实格式错误仍报错 |
| 聊天全文索引使用 rowid 映射 | 已适配，验证见 PR | 旧索引只补映射；保留现有按节点增量更新；会话隔离删除；修改标题／时间立即反映到搜索结果；事务回滚 |
| MCP 列表滚动修复 | 结构不适用，保留设备观察 | 上游补丁针对 ListItem.supportingContent 内的多子节点布局；本项目服务器列表使用 Card + Row，不照搬 |
| 超长消息触摸修复 | 触发结构不同，需设备验证 | 上游补丁针对不等圆角产生的 Path 图层轮廓；本项目气泡为等圆角，并有独立玻璃效果，不直接替换 |

来源与适配：

- [服务端 function_call_output](https://github.com/rikkahub/rikkahub/commit/675e59f0dfebe86bd4ecf2be04cc62f125dddf69)：本项目保留 MessageChunk 架构，增加每次请求独立的工具 ID 和快照状态；不引入整个新流式框架。
- [中转空值兼容](https://github.com/rikkahub/rikkahub/commit/240deb5fe2)：应用到旧解析器，保留本项目多行 SSE 与错误网关拦截。
- [全文索引优化](https://github.com/rikkahub/rikkahub/commit/90e48420fb9511649ef93f0fe616b5919f4f82e5)：映射增加 node_id，支持本项目已经存在的节点级增量索引。保留中文分词器、原文排除注入及长度限制。
- [MCP 列表布局](https://github.com/rikkahub/rikkahub/commit/42896e5ad4)、[长消息图层](https://github.com/rikkahub/rikkahub/commit/9b458a67ce)：保留分析结果，未宣称本项目已复现同一问题。

验证命令：

```bash
python3 scripts/verify-message-fts.py
./gradlew testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.data.db.migrations.StableMemoryUpgradeTest,me.rerere.rikkahub.data.db.fts.MessageFtsManagerTest
./gradlew lintDebug
./gradlew lintRelease assembleRelease
```

主机 SQL 检查使用标准分词器验证生产 SQL；Android 仪器测试使用真实 libsimple 和 Room。
不得把“仪器测试已编译”写成“设备测试已通过”。PR 中记录对应代码提交的 CI 状态与运行链接。
设备测试使用独立的临时文件数据库：Requery 的内存数据库路径会绕过扩展配置，无法加载 libsimple。

## 第二批：日常功能（进行中）

按依赖顺序分开提交，避免同时修改整个聊天循环：

1. 用量累计与模型生成耗时：PR #20 已验证并合入。同一请求合并快照，工具多轮独立请求累加；流式从首帧到结束计时，排除本地回调耗时和请求间工具／确认等待；非流式使用整个请求耗时。主动消息保留原有不写消息用量的规则。手机与网页同步显示，新字段缺省不改变旧消息的序列化内容。
2. 用量统计页：当前分支已实现，待 Android CI 验证。按近 7／30／90 天及全部，汇总输入、输出、缓存、请求数、趋势、模型与用途；新建每请求账本，保留旧聊天热力图和历史概览。正式版仍隐藏请求体积和召回调试信息。
3. 消息发送队列：正在生成时排队，支持编辑／删除；明确取消、切换会话、应用重启和附件生命周期。
4. 会话独立配置：模型、思考、搜索、MCP、技能等设置的覆盖与持久化；后台工作流仍用实际执行角色的工具。
5. 模型上下文长度设置与用量显示：区分模型计费 token、上下文估算、请求字节体积；兼容现有摘要检查点。
6. 原生图表工具与图表展示。
7. 翻译历史记录。
8. DeepSeek 搜索／网页读取、Gemini Interactions API 等独立接口适配。
9. 语音回复期间是否持续监听的可选设置，保留当前通话和打断机制。

用量基础适配来源：[上游累计与耗时修复](https://github.com/rikkahub/rikkahub/commit/e7f2c04f76974b2613404385e21db999e910b29c)。
请求用量账本与统计页适配上游 `43f1a5df24adc09b6720ae2e8aa568a12a5bf43a` 的 UsageRecorder／UsageRecordDAO。
当前账本从升级后开始记录，不将旧消息推算成完整的历史 API 用量。每轮工具调用、每次显式重试各记一行；同次流式快照合并后只记一次。
覆盖聊天、标题、建议回复、压缩、翻译、记忆整理、OCR、主动消息／拉黑续聊、通话事件、插件及连接测试。
工作流执行工具本身不产生模型用量；若工具触发上述生成入口，按该入口用途记录，不另增一条虚构的“工作流请求”。
仅保存时间、用途、模型／提供商与归属 ID、token 数，不保存请求正文或密钥。不统计缺少 usage 的请求、嵌入或图片生成接口。
异步写库不阻塞回复，不增加 API 请求；应用进程在写入前被系统终止时可能漏掉最后一笔，统计不是计费账单。
Room v38→v39 只增加 usage_record 及时间索引；不修改记忆、聊天或表情数据，不依赖会话外键。
本地 `python3 scripts/verify-usage-stats.py` 验证真实迁移 SQL、日期边界、模型与提供商隔离、删除保留、64 位累计和事务回滚；Android 另测 Room 迁移及 DAO。
新增回归覆盖工具多轮累计、重复快照、缓存 token、usage-only 尾帧、首帧等待、工具等待、本地回调、非流式耗时、异常／取消、旧消息序列化及本地字段不发给服务端。
所有批次完成后统一回归并发布；本分支工作流仅构建安装包，不创建 Release。

## 第三批：较大模块（尚未实现）

- ChatGPT／Grok 订阅账号接入及额度显示。
- 统一图片／视频创作模块与批量管理；迁移原有图片生成记录。
- 完整远程 SSH 工作区；复用现有加密主机、SSH 命令与文件传输功能。
- 安装包瘦身：分别评估 PDF 解析、扫码、裁剪、分词词典和前端依赖替换。逐项验证格式兼容和体积，不套用上游体积数字。

## 已有能力与单独评估项

- 已有摘要压缩、保留最近消息、自动整理与按需召回记忆、文件夹、语音通话、SSH 工具、基础 MCP 授权；不重复实现。
- 上游文件式记忆／自动做梦与本项目数据库、来源证据和召回架构不同；不随功能同步替换，迁移需另行设计。
- 不整套覆盖上游 UI：保留分气泡、头像、表情包、拍一拍、玻璃效果、主动消息、工作流和现有设置。
- APK 瘦身不等于减少每轮工具描述 token；工具小卡片／按需展开仍是另一项实验。

发布说明：[3.1.0](https://github.com/rikkahub/rikkahub/releases/tag/3.1.0)、[3.0.0](https://github.com/rikkahub/rikkahub/releases/tag/3.0.0)、[2.5.6](https://github.com/rikkahub/rikkahub/releases/tag/2.5.6)。
