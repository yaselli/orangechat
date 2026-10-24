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

按依赖组织实现与回归；用量统计、发送队列、会话配置与上下文显示集中在 PR #21，完成一组后统一验收：

1. 用量累计与模型生成耗时：PR #20 已验证并合入。同一请求合并快照，工具多轮独立请求累加；流式从首帧到结束计时，排除本地回调耗时和请求间工具／确认等待；非流式使用整个请求耗时。主动消息保留原有不写消息用量的规则。手机与网页同步显示，新字段缺省不改变旧消息的序列化内容。
2. 用量统计页：当前分支已实现。首轮完整 550 项单元测试、设备迁移与真实 Room DAO／FTS 回归、双版本 lint 与 APK 构建通过；编译器导出的 v39 schema 已补入，最终按需观察优化随新提交复验，见 PR #21。按近 7／30／90 天及全部，汇总输入、输出、缓存、已记录请求数、趋势、模型与用途；新建每请求账本，保留旧聊天热力图和历史概览。正式版仍隐藏请求体积和召回调试信息。
3. 消息发送队列：已接入，新增回归待本轮 CI。生成中排队、编辑／撤回、停止或失败后暂停并手动继续；切换会话保留，进程退出不保留。拍一拍注解与录音附件保留，附件清理检查所有分支及队列引用；主动消息不能越过排队输入，语音完成绑定自己的队列项。
4. 会话独立配置：已接入，新增回归待本轮 CI。模型、思考、搜索、MCP、技能及请求参数按会话冻结并持久化，可显式同步角色默认；空选择不回退继承。角色提示词、记忆与外观仍共用，后台工作流仍用实际执行角色的工具。
5. 模型上下文长度设置与用量显示：已接入，新增回归待本轮 CI。上限是用户填写的模型共享元数据；显示当前会话最近一次聊天／主动请求的输入 token，不叠加工具多轮，不混入标题／翻译，不冒充下一请求预算。未知上限不猜测，不触发自动压缩，摘要检查点单独保存。
6. 原生图表工具与图表展示：已接入，新增回归待本轮 CI。角色本地工具可选开启 chart_display，支持折线／柱状／散点、图例、数值／对数轴、数据表切换。仅成功且参数合法的调用渲染为独立卡片，保留工具轨迹；大量表格行按需绘制。已有冻结配置的会话需显式同步角色默认以启用新工具。
7. 翻译历史记录：已接入，新增回归待本轮 CI。翻译页保存最近 100 条完整成功结果，分页查看、恢复原文／译文／目标语言、左滑删除、确认清空；目标语言持久化，可将结果放回输入。取消／失败不存半条结果，新任务不会被旧任务结束回调关闭。消息气泡内的翻译保持原流程，不混入翻译页历史。
8. DeepSeek 搜索／网页读取、Gemini Interactions API：已接入，待本轮 CI 与统一验收。搜索服务列表可添加 DeepSeek，带独立 Key；Gemini 提供商可选 Interactions，默认仍为原接口，Vertex AI 保留原路径。Interactions 使用本地管理历史与 store=false，兼容工具分组、思考签名、服务器工具结果及输入图片／音频；用请求内状态生成旧 MessageChunk 的权威快照，避免重复拼接。流缺少完成事件会报错，不将半个调用当成功。
9. 语音回复期间继续监听：已接入，待本轮 CI 与统一验收。语音设置 ASR 页增加开关，默认 true 保留现有行为；关闭时在生成／朗读阶段暂停采集，回到聆听恢复，静音优先，仍可手动打断。

用量基础适配来源：[上游累计与耗时修复](https://github.com/rikkahub/rikkahub/commit/e7f2c04f76974b2613404385e21db999e910b29c)。
请求用量账本与统计页适配上游 `43f1a5df24adc09b6720ae2e8aa568a12a5bf43a` 的 UsageRecorder／UsageRecordDAO。
当前账本从升级后开始记录，不将旧消息推算成完整的历史 API 用量。每轮工具调用、每次显式重试各记一行；同次流式快照合并后只记一次。
覆盖聊天、标题、建议回复、压缩、翻译、记忆整理、OCR、主动消息／拉黑续聊、通话事件、插件及连接测试。
工作流执行工具本身不产生模型用量；若工具触发上述生成入口，按该入口用途记录，不另增一条虚构的“工作流请求”。
仅保存时间、用途、模型／提供商与归属 ID、token 数，不保存请求正文或密钥。不统计缺少 usage 的请求、嵌入或图片生成接口。
异步写库不阻塞回复，不增加 API 请求；应用进程在写入前被系统终止时可能漏掉最后一笔，统计不是计费账单。
Room v38→v39 只增加 usage_record 及时间索引；不修改记忆、聊天或表情数据，不依赖会话外键。
v39 由本分支 CI 的 Room 编译器实际导出，已核对原有 20 个实体与 v38 完全一致；新增表迁移与编译 schema 的元数据匹配。
统计页无订阅者时停止观察和查询，避免切回聊天后持续汇总账本。
本地 `python3 scripts/verify-usage-stats.py` 验证真实迁移 SQL、日期边界、模型与提供商隔离、删除保留、64 位累计和事务回滚；Android 另测 Room 迁移及 DAO。
新增回归覆盖工具多轮累计、重复快照、缓存 token、usage-only 尾帧、首帧等待、工具等待、本地回调、非流式耗时、异常／取消、旧消息序列化及本地字段不发给服务端。
所有批次完成后统一回归并发布；本分支工作流仅构建安装包，不创建 Release。

## 第三批：较大模块（进行中）

- ChatGPT／Grok 订阅账号接入及额度显示：本分支已接入，待最新 CI 与统一验收。
- 统一图片／视频创作模块与批量管理；迁移原有图片生成记录。
- 远程 SSH 工作区：本分支已接入，待最新 CI 与统一验收；原有 SSH 工具继续保留。
- 安装包瘦身：分别评估 PDF 解析、扫码、裁剪、分词词典和前端依赖替换。逐项验证格式兼容和体积，不套用上游体积数字。

## 已有能力与单独评估项

- 已有摘要压缩、保留最近消息、自动整理与按需召回记忆、文件夹、语音通话、SSH 工具、基础 MCP 授权；不重复实现。
- 上游文件式记忆／自动做梦与本项目数据库、来源证据和召回架构不同；不随功能同步替换，迁移需另行设计。
- 不整套覆盖上游 UI：保留分气泡、头像、表情包、拍一拍、玻璃效果、主动消息、工作流和现有设置。
- APK 瘦身不等于减少每轮工具描述 token；工具小卡片／按需展开仍是另一项实验。

发布说明：[3.1.0](https://github.com/rikkahub/rikkahub/releases/tag/3.1.0)、[3.0.0](https://github.com/rikkahub/rikkahub/releases/tag/3.0.0)、[2.5.6](https://github.com/rikkahub/rikkahub/releases/tag/2.5.6)。

本轮 Room v39→v40 为 ConversationEntity 增加默认空的 conversation_config，并为上下文账本查询加组合索引。
现有普通保存保留专属配置列，防止较旧流式快照覆盖；旧会话在下一轮请求或显式调整时绑定默认。
本地主机检查已验证 v39→v40 所有旧列／记录保留、回滚、配置专用 SQL、查询用途隔离及共享附件引用；
v40 schema 已从 4ad0d5f 的真实 Room 编译产物提取，主机检查确认所有表列、索引与外键符合生产迁移。
4ad0d5f 的 JVM 与设备测试编译已通过；后续语音串轮防护及整组双版本检查仍以最新提交 CI 为准，未通过前不标记本轮完成。

图表／翻译历史来源仍固定为上游 `43f1a5df`，保留旧 GenerationHandler 和聊天分组架构。
Room v40→v41 仅新增 translation_history 及时间索引；保存与裁剪同事务，避免出现半保存历史。
主机检查覆盖所有旧表／数据、回滚、保留最新 100 条、同时间稳定排序和删除；设备检查扩展为 v29…v40→v41。
上一轮 368f1a5 的 Debug lint 因队列新增文案缺少 ja/ko/ru 翻译失败，本轮补齐各语言，不修改 lint 基线或关闭检查。
新图表及历史 JVM／设备检查、双版本 lint／APK 构建以本轮 CI 为准；未通过前仍保持草稿，不发布更新。

v41 schema 已从 3b8ba15 的 Room/KSP 实际编译产物提取，生产迁移与编译导出的表列、索引、外键全部匹配。
该提交的应用编译发现长图导出同样使用消息分组，已补齐 ChartBlock 渲染；不能将此次 Room 生成成功表述为全应用编译或单测通过。
翻译流在页面协程收集并更新显示，后台请求不直接回写页面状态；最终 JVM、设备测试及双版本结果仍待复验。

第二批剩余接口与语音来源仍对齐 `43f1a5df`；协议形状另核对官方文档：
- https://api-docs.deepseek.com/api/search/ 与 https://api-docs.deepseek.com/api/fetch/
- https://ai.google.dev/gemini-api/docs/interactions-overview 与 https://ai.google.dev/api/interactions-api

本轮不引入上游整个 StreamChunk 框架：仅为 MessageChunk 增加默认关闭的快照标记，原有提供商仍使用增量语义。
Interactions 的原始服务端工具调用／结果保存在其命名空间 metadata，输出始终标记已执行，不走手机工具调用；
跨协议的 GenerateContent 思考签名不混回 Interactions。请求和 SSE 正文不写日志，工具与搜索模拟测试不使用真实 Key 或付费请求。
目前不迁移服务端会话续接、后台 Deep Research 或 Vertex Interactions；旧接口和 Vertex 的既有功能不替换。
12425e9 的 Debug 单测与设备测试编译通过，但 lint 命中翻译历史非可观察 Locale，本轮改为 LocalConfiguration 读取。
第二批功能实现清单已覆盖；仅在最新 CI 与统一真机验收通过后才标记第二批完成，当前仍不发布或合入 master。

### 真机验收后的界面调整

- 会话设置入口移入输入栏「＋」展开菜单，点击后收起菜单并打开原设置弹窗。
- 按用户反馈移除输入框上方重复的上下文用量条，保留消息原有用量显示；不改用量记账和数据库。
- 用户反馈本轮测试未发现其他问题；这次入口调整仍需最新 CI 编译验证。

### 第三批第一组：PDF／扫码／裁剪瘦身（2026-10-24）

- 固定适配同一上游 `43f1a5df` 的纯 Kotlin PDF 文字解析器与 15 份 PDF 夹具；覆盖损坏 xref、对象流、增量保存、中文、纵排、RTL、字体编码、空密码加密和带密码拒绝。移除未再引用的 MuPDF Java 包与两份原生库，保持 `PdfParser.parserPdf` 的调用接口、分页文本格式。扫描件仍依赖另行 OCR，不会凭空变成文字；密码保护文件不绕过密码。
- 二维码扫描改用 CameraX 与现有 ZXing，移除 Quickie 和 bundled ML Kit 条码识别。保留相册扫码、供应商配置解码与原错误提示；权限拒绝、取消、无后置相机不会导入。屏幕 OCR 的 ML Kit 文字识别保留。
- 图片裁剪适配上游 Compose 实现，保留拖动、缩放、旋转、自由／固定比例与 EXIF 方向；移除原生 uCrop，继续限制结果最长边 4096。按本项目原行为输出 PNG，保留透明度；保存或取消后清理临时文件。
- 回归使用上游 PDF 夹具与文本断言、二维码带行填充帧及无效帧、旋转裁剪坐标测试。APK 构建后记录实际 ZIP／ABI／原生库体积，并检查替换掉的三套原生引擎未被间接打包；未做同规格对比前不宣称节省数值。
- 分词词典与前端依赖尚未替换；订阅账号、统一媒体创作和远程 SSH 仍待后续适配。该组最新 CI 未通过前不标记验收完成，不发布更新。

### 第三批第二组：远程 SSH 工作区（实现中，待 CI）

- 仍使用上游 `43f1a5df` 的 SSH/SFTP 后端、连接池、可取消通道与主机指纹校验；将其接入本项目现有工作区列表、文件浏览/上传/导出、AI 文件工具和交互终端。
- 终端引入上游 `terminal` 模块的 transport 接口，本地 PTY 与远程 SSH 共用终端显示；移除工作区模块重复的 libtermux 编译，保留本项目 PRoot、DNS 和绑定目录逻辑。
- SSH 配置独立保存，密码/私钥/口令复用本项目 SecretCrypto，以工作区 ID 和字段名绑定密文。表单凭据不进 saved state、不写日志；首次连接和主机指纹变化需明确确认。更换地址会清空已信任指纹。
- Room v41→v42 仅为 workspaces 添加 type（旧记录默认 LOCAL）和 nullable ssh_config，保留旧工作区、工具审批及所有记忆/消息/翻译表。真实 v42 schema 从 CI 编译器取得，未伪造 identityHash；主机与 Android 迁移检查均扩展验证。
- 远程工作区不安装本地 rootfs；系统提示使用实际远程路径，不声称远程存在本机 /skills 或 /upload。删除工作区只删除本地记录/缓存并关闭连接，绝不递归删除远程目录。
- 系统 DocumentsProvider 仍仅暴露本地工作区；远程文件从工作区页面浏览和导入导出。
- 回归复用上游嵌入式 SSH/SFTP 服务器（不连接用户服务器），覆盖指纹、密码/私钥、文件操作、命令超时、取消、断线、连接池清理与 PTY；增加移动文件不覆盖的检查，同时运行本地终端原有回归。
- 上一组 b805347 的 JVM 测试通过，设备测试编译发现 ExifInterface 构造函数在当前依赖需传路径字符串，已修正。最终结果以本组最新提交为准。

2ff7f5d 的 Release 流程 JVM 单测、设备测试编译与 v42 迁移检查已通过。真实 v42 schema 从该运行的 `room-v42-release` 产物提取，identityHash 为 `1bb0bc21826aa40c03d459ea00d972bd`；主机核对生产迁移与编译 schema 的所有旧表、列、索引及外键完全一致。最新设备运行、Lint 与 APK 仍需看对应提交结果，不能将单测通过写成整体验收通过。

### 第三批第三组：订阅账号与额度（2026-10-24，待 CI）

- 固定适配上游 `43f1a5df` 的 OAuth/PKCE、ChatGPT Codex 授权码登录、Grok 设备码登录、令牌刷新与额度解析。提供商新增独立账号类型，不将 OAuth token 当成 API Key；原 API 提供商与 GenerateContent/Chat Completions 继续保留。
- 账号设置页显示登录状态、邮箱与订阅信息，可取消授权、重新登录和退出；额度由按钮手动查询，不后台轮询、不根据消息账本猜计费额度。模型目录与订阅窗口按服务商响应解析。
- 复用本项目旧 MessageChunk/Responses 解析器，仅为账号端点增加明确的协议选项，不整套覆盖上游流式架构。Codex 使用 developer 输入、无服务端存储、强制 SSE 并移除不支持的采样参数；处理成功 SSE 缺 Content-Type。Grok 不发送不支持的 reasoning.summary/档位。保留全部 system 提示，缺少完成事件报错。
- 请求发出时读取最新凭据，401 最多刷新重试一次；凭据和保留头不能被自定义头覆盖，账号及令牌端点不跟随重定向。HTTP 错误不附令牌响应原文，OAuthCredential 日志字符串完全隐藏令牌。
- 凭据跟随 providers 偏好在本机加密保存。普通设置编辑在写锁下保留最新凭据；只有账号管理器能更新会话，避免旧页面覆盖轮换后的 refresh token。按提供商串行刷新，调用方取消不丢失已开始的刷新结果；退出/删除/变更类型后不写回旧结果。取消或重开登录会使上一授权的结果失效。
- 分享提供商、Web Settings SSE 与 S3/WebDAV 备份均剔除订阅登录凭据及嵌套模型覆盖项中的凭据；恢复或导入配置需重新登录。仅这类凭据采用新规则，不顺带改写原 API Key 备份语义。
- 旧插件若依赖直接注入模型 API Key，不向其暴露订阅令牌，应继续选择 API Key 模型；账号暂不作为嵌入、原始录音或独立图片生成接口。使用应用提供商生成路径的普通聊天与工具调用按现有流程处理。
- 回归使用 OAuth 本机临时回调服务器、伪造 HTTP/SSE 与内存原子账号存储，不登录真实账号、不消耗订阅或付费 Key。覆盖 PKCE/state、设备轮询、JWT 字段、刷新取消/退出/并发、陈旧编辑、嵌套分享清理、Codex 请求/流及额度解析。
- 前一提交 9be5387 的 Lint 修正漏加 LocalResources import，本组已补齐；不关闭 Lint、不修改基线。最新 CI 未通过前不宣称账号组验收完成，不发布更新。
