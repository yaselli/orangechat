# 上游补全清单

对照日期：2026-10-23。维护基线：`build/stable-release` 的 `2f9f63ce`。
上游基线：RikkaHub 3.1.0，以及开发提交 `43f1a5df24adc09b6720ae2e8aa568a12a5bf43a`。
两边版本号独立；本清单按代码和功能对照，不代表落后多少个版本或提交。

工作分支：`fix/upstream-alignment-batch1`。所有 PR 以 `build/stable-release` 为目标。
每批先适配、测试，再单独合并；不向本仓库 `master` 合并。

第一批实现与最新验证结果：[PR #19](https://github.com/yaselli/orangechat/pull/19)。

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

## 第二批：日常功能（尚未实现）

按依赖顺序分开提交，避免同时修改整个聊天循环：

1. 用量累计与模型生成耗时：先核对工具多轮调用、取消、失败、主动消息和工作流，再建立统计数据来源。
2. 用量统计页：按日期、模型、用途汇总；保留正式版隐藏请求体积和召回调试信息的规则。
3. 消息发送队列：正在生成时排队，支持编辑／删除；明确取消、切换会话、应用重启和附件生命周期。
4. 会话独立配置：模型、思考、搜索、MCP、技能等设置的覆盖与持久化；后台工作流仍用实际执行角色的工具。
5. 模型上下文长度设置与用量显示：区分模型计费 token、上下文估算、请求字节体积；兼容现有摘要检查点。
6. 原生图表工具与图表展示。
7. 翻译历史记录。
8. DeepSeek 搜索／网页读取、Gemini Interactions API 等独立接口适配。
9. 语音回复期间是否持续监听的可选设置，保留当前通话和打断机制。

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
