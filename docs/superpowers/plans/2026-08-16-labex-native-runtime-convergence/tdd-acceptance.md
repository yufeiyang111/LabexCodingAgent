# LabexAgent 原生运行时 TDD 测试与验收文档

> 状态：用户已于 2026-08-16 确认 S1 至 S6 public seam；已开始阶段 A 的 Red -> Green 垂直切片。
>
> 对应实施计划：`D:/LabexAgent/docs/superpowers/plans/2026-08-16-labex-native-runtime-convergence/spec.md`。
>
> 方法：严格采用 Red -> Green 的垂直切片，不先批量写测试，也不先进行大范围重构。

## 1. TDD 约束

1. 每条测试验证一个用户或调用方可观察到的行为，而不是 private method、内部 Map 或临时日志。
2. 每次只选择一个 public seam：先写失败用例，确认红灯，再做最小实现让它变绿，再进入下一条。
3. 预期值必须来自已确认的协议、持久化事实或用户可见结果，不能在断言里重新实现被测逻辑。
4. 外部 Provider、网络、进程和 MCP 可以在测试中使用明确声明的 fake 或 fixture，但业务路径不能留下 mock、假成功或隐式 test profile。
5. 失败的 tool、批准等待、恢复、连接中断均需通过公开事件、API 或持久化 projection 观察，不能只断言内部方法是否被调用。
6. 代码重构不和红绿切片混做；每完成一组稳定切片后再单独审查和重构。

## 2. 已确认的测试 seam

以下 seam 是开始写测试源码前必须确认的公共观察边界。它们与当前架构一致，避免测试耦合到内部实现。

| 编号 | Public seam | 可观察结果 | 首个 TDD 切片 |
|---|---|---|---|
| S1 | 对话创建与流式请求 API | profile 被保存、冲突被拒绝、task snapshot 稳定 | 新旧 conversation profile 隔离 |
| S2 | `AgentLoopEngine` 的 start、resume 与 durable Run API | task、Part、Event 的顺序和状态可重建 | native 单回合多 tool call |
| S3 | `AgentTool` 通过 `ToolRegistry` 的公开执行契约 | tool result 含状态、目标身份、证据、幂等归属 | shell 失败与删除真实落盘 |
| S4 | workspace 与 verification 的服务边界 | 文件系统、Git/change-set、verification 事实一致 | 删除后文件树与验证一致 |
| S5 | SSE event 到前端 reducer 的边界 | 刷新、重连后 final 与计划正确重放 | final 已生成但未显示修复 |
| S6 | provider schema 生成边界 | 当前回合只有许可且可用的核心与扩展工具 | 按需 exposure snapshot |

确认记录：用户已于 2026-08-16 确认上述六个 seam 是本轮测试唯一的 public 观察边界。若某个 seam 的接口需要调整，应先更新本表和实施计划，不借测试窥探内部实现。

## 3. 关键验收场景与红绿顺序

### 切片 1：Profile 隔离

**Red**：创建 legacy 与 native 两个 conversation 后，分别启动 task；断言各 task 使用创建时 snapshot，向已有 conversation 传入不同 profile 得到明确冲突。

**Green**：只实现 enum、字段迁移、service snapshot 与 API 校验，不提前实现 native processor。

**验收**：两个 conversation 可在同一项目并存；历史空 profile 仍稳定路由 legacy。

### 切片 2：Native durable tool batch

**Red**：使用受控 Provider fixture 返回两个 tool call。通过 Run Message、Part 和公开事件断言两条 call 都先持久化、结果同 ID 配对、按确定性顺序完成。

**Green**：只引入 native turn processor 的最小分派与 durable batch 逻辑，复用现有 lifecycle 与 tool executor。

**验收**：中断或审批发生时未执行的第二条 call 显示 `interrupted` 或 `skipped`，重试不会重复执行第一条。

**2026-08-16 实施证据**：`LabexNativeToolBatchExecutorTest` 先覆盖组件协议；`LabexNativeToolBatchDurableDatabaseTest` 通过受控 streaming Provider、真实 H2/MyBatis `AgentTask`/Run Message/Part/Event/outbox，验证正常双工具 turn 在第一条 delegate 前已持久化全部 `tool_call` Part，且结果按 `toolCallId` 配对；另验证 approval 后 task 未被 batch 伪终结、第一条 Part 为 `waiting_approval`、第二条为 `skipped`。此为 S2 的 durable batch 子切片，不替代后续 `AgentLoopEngine` public start/resume、断线和浏览器 replay 验收。

### 切片 3：真实 shell 失败不被说成成功

**Red**：通过公开 shell tool seam 执行一个确定性非零命令；断言 Part 记录非零 exit、verification 不是成功、final projection 不产生成功完成事实。

**Green**：只补齐结构化 shell result 和 completion evidence 消费规则。

**验收**：模型可以产生解释文本，但 UI 与 durable verification 必须同时显示真实失败。

### 切片 4：删除声明与真实文件系统一致

**Red**：先创建 fixture 文件，再由 tool 删除；断言文件真实不存在、target identity 指向正确 worktree、`WORKSPACE_CHANGED` 产生且 verification 记录变更。再构造删除失败，断言模型没有可被 accepted 为完成的虚假证据。

**Green**：只接入 target identity、mutation evidence、workspace event 与 failure propagation。

**验收**：复现历史 Skill 删除事故时，系统能明确区分未执行、执行失败、已删除三种状态。

### 切片 5：Final durable replay

**Red**：写入已 accepted 的 final 后模拟 SSE 断线、页面刷新、重新载入 conversation；断言 reducer 仍显示最终答复。构造 rejected 与 interrupted，断言可见原因。

**Green**：只实现 native completion projector 与 reducer event handling，不改无关 UI 样式。

**验收**：不再出现日志有最终总结、前端对话区没有最终消息的截断现象。

### 切片 6：计划是投影而非完成门槛

**Red**：native run 无 `create_plan`、`plan_exit`、`todo_write` 调用时完成一个只读问题或简单改动；断言 final accepted 不受计划工具影响。另断言已有 Plan Part 仍能渲染。

**Green**：只移除 native completion 对控制工具的依赖，保留 legacy replay。

**验收**：模型不被工作流式计划强制束缚，侧边栏仍展示可用执行进度。

### 切片 7：按需扩展暴露

**Red**：在相同 conversation 上，以不同 capability、附件、LSP client、Skill、MCP 连接状态建立请求；断言 schema 与 snapshot 只包含符合条件的工具。

**Green**：只实现 `ToolExposurePlanner` 和 snapshot 持久化，不改变底层工具实际行为。

**验收**：恢复同一 request 使用原 snapshot；MCP 断线、图片缺失、LSP 不可用都有明确而有限的失败。

### 切片 8：恢复、压缩与循环保护

**Red**：构造审批等待、断线、上下文压缩、重复无进展 tool call 四类中断；从公共 resume seam 断言 epoch、Part 状态、停止原因和 final 可见状态一致。

**Green**：只在 lifecycle、processor 或 projector 需要处加最小恢复逻辑。

**验收**：不会把 waiting state 伪造成终态，也不会把同一 prompt 无限重试。

## 4. 测试层级与数据边界

| 层级 | 使用场景 | 允许的替身 | 禁止事项 |
|---|---|---|---|
| 后端纯单测 | profile、schema filter、event reducer、identity 归一化 | 明确 fixture、fake Provider response | mock private method 或断言内部 Map |
| 后端服务集成测 | conversation/task、Part/Event、permission、tool result | 测试数据库、受控 workspace | 用日志文本代替 durable state |
| 工具集成测 | shell、读写、删除、Git/change-set、verification | 临时测试 workspace 与确定性命令 | 操作真实用户 workspace 或 host 外路径 |
| 前端 reducer 测 | SSE event、replay、final、workspace refresh | 固定 durable event fixture | 根据连接关闭猜状态 |
| 浏览器 smoke | conversation 创建、流式执行、刷新、文件树 | 专用 acceptance 项目 | 依赖 mock 页面数据 |

测试 fixture 必须创建在受控临时 workspace，测试结束后只删除其自身显式创建的目录。不得读取、打印或写入 `.env`、凭据和用户真实项目文件。

## 5. 验收矩阵

| 能力 | 正向证据 | 负向证据 | 完成条件 |
|---|---|---|---|
| Profile | task 与 conversation profile 一致 | 冲突 profile 被拒绝 | 历史与新会话并存 |
| Transcript | tool call/result 配对且可重放 | 缺 ID、重复执行、半批状态可见 | 多 call、resume 通过 |
| Shell | exit 0 与验证证据一致 | exit 非零不标成功 | 真实失败可见 |
| 删除 | 文件、diff、Part、tree 一致 | 删除失败不会自述成功 | 历史事故复现通过 |
| Final | accepted 在刷新后显示 | rejected/interrupted 有理由 | 对话不截断 |
| 计划 | UI 可显示 progress | 无 plan tool 仍可完成 | 无 completion gate |
| 工作区刷新 | 当前 task 变更刷新 tree | 其他 task 不误刷新 | project guard 通过 |
| 扩展工具 | 条件满足才暴露 | 不可用时有限失败 | snapshot 可恢复 |
| 安全 | 正常工程命令可运行 | destructive/越界有保护 | 不阻碍常规开发 |
| 循环保护 | 无进展有限停止 | 不无限重试 | 停止原因可解释 |

## 6. 每条测试的最小验收记录

每个红绿切片需要在测试名称或临近文档记录以下内容：公共 seam、用户可观察行为、失败时的独立预期、最小实现文件、执行命令、红灯证据、绿灯证据、未覆盖风险。不得用一次全量构建成功代替行为验收。

## 7. 执行顺序与命令原则

1. 先运行最小相关后端测试，再扩大到相关 package，最后运行完整后端测试。
2. 前端使用仓库实际存在的测试执行方式；当前没有 package 脚本时，实施前先确认 `.test.mjs` 的实际 Node runner 命令，不新增测试框架或脚本来凑验收。
3. UI 改动在 reducer 测试通过后进行浏览器 smoke；浏览器连接状态不能作为唯一证据。
4. 每个阶段结束检查 `git diff`，确认没有混入无关重构、mock 业务数据或未受控配置常量。

## 8. 已确认事项与阶段 A 执行记录

用户已于 2026-08-16 确认：S1 至 S6 是本轮唯一允许的 public seam。实施从 profile 隔离开始，仍按逐条 Red -> Green 执行，不一次性重写 `AgentLoopEngine`。

| 切片 | Public seam | Red 证据 | 最小 Green 实现 | 绿灯验证 | 未覆盖风险 |
|---|---|---|---|---|---|
| A1：conversation/task profile | S1 | 新 profile 字段与服务 snapshot 尚不存在时，创建/冲突/任务 snapshot 的回归无法满足 | `AgentRuntimeProfile`、conversation/task 字段、additive schema、请求映射、service snapshot | `AgentConversationRuntimeProfileTest`、`AgentTaskServiceTest`、`AgentStreamRuntimeProfileRequestTest`、`StudentAgentControllerStreamSecurityTest` | background/approval 的完整恢复链尚未做黑盒验收 |
| A2：恢复 profile | S2（durable continuation request） | `AgentRunContinuationRequestFactoryTest` 断言期望 `labex-native`，实际为 `null` | continuation request 固定从 `AgentTask.runtimeProfile` 重建 | `mvn -q -Dtest=AgentRunContinuationRequestFactoryTest test` | 尚未进入 native turn processor |
| A3：fork profile | S1 | `AgentConversationRuntimeProfileTest` 期望 fork 继承 `labex-native`，实际为 `null` | fork 复制 source conversation 的 profile snapshot | `mvn -q -Dtest=AgentConversationRuntimeProfileTest test` | 后台分支与 interaction 恢复待覆盖 |
| A4：执行 profile 权威来源 | S2（任务执行准备边界） | `AgentRuntimeProfileResolverTest` 在 resolver 尚不存在时测试编译失败 | 新增 resolver；`AgentLoopEngine` 在 task 已确定后将 request profile 归一为 task snapshot，并将其投影到 SESSION 元数据 | `mvn -q -Dtest=AgentRuntimeProfileResolverTest test`；`mvn -q -DskipTests compile` | 当前仅建立 durable 路由边界；native 处理器尚未替换 legacy turn loop |

| A5：产品化执行 profile 名称 | S6（Provider 请求配置） | `AgentExecutionPropertiesTest` 将默认值和生产降级预期改为 `labex-standard` 后，旧实现仍返回旧外部名称 | 统一执行 profile 默认值、生产校验、prompt 默认值和配置文档 | `mvn -q -Dtest=AgentExecutionPropertiesTest test`；相关配置测试 | 仅完成执行 profile 命名收敛，不改变 destructive-operation 边界 |
| A6：native Provider 投影 | S6（system prompt + tool schema） | native prompt/schema overload 尚不存在，`LabexSystemPromptTest` 与 `ToolSelectionPolicyTest` 编译失败 | native profile 注入直接、证据优先提示词；按 profile 的 schema 过滤隐藏 `todo_write` 与 `plan_exit`，Engine 从 task snapshot 投影 prompt/schema | `mvn -q -Dtest=LabexSystemPromptTest,ToolSelectionPolicyTest test`；`mvn -q -DskipTests compile` | 尚未抽出完整 native turn processor；Part/Event batch 仍使用现有 durable loop |

### 切片 B1：Native durable tool batch bridge（已完成，尚未关闭 S2）

**Red**：新增 `LabexNativeToolBatchExecutorTest` 后，类与协议不存在导致 test compile 失败；新增 Engine routing contract 后，native profile 尚未调用 batch executor 导致断言失败。

**Green**：新增 `AgentProviderTranscriptAppender` 与 `LabexNativeToolBatchExecutor`。同一模型 turn 先追加 assistant `tool_calls`、再将所有可执行 call 记为 `pending`，随后通过现有 Engine 工具包装串行执行；每个结果按原始 `toolCallId` 追加 role=`tool`。approval 会让后续可执行 call 变为 `skipped`，取消会让尚未开始的 call 变为 `interrupted`。`labex-native` 已从现有大循环显式分派到该 bridge；legacy 不变。

**绿测**：

```text
mvn -q -Dtest=AgentProviderTranscriptAppenderTest,LabexNativeToolBatchExecutorTest,AgentLoopEngineNativeToolBatchWiringTest test
mvn -q -Dtest=LabexNativeToolBatchDurableDatabaseTest test
mvn -q -Dtest=AgentProviderTranscriptAppenderTest,LabexNativeToolBatchExecutorTest,LabexNativeToolBatchDurableDatabaseTest,AgentLoopEngineNativeToolBatchWiringTest,AgentToolCallBatchProtocolTest,AgentToolTurnExecutorTest,AgentRunTranscriptServiceTest,AgentToolCallJournalServiceTest,AgentLoopEngineProcessorContractTest,AgentLoopEngineParallelToolCallTest,AgentLoopEngineLoopPolicyTest,AgentLoopEngineFinalReplyPolicyTest,AgentLoopEngineCompactionTerminalityTest,AgentLoopEngineNextPreviewTest,AgentLoopEngineWiringContractTest,AgentLoopEngineContextBudgetTest,AgentRuntimeProfileResolverTest,AgentRunContinuationRequestFactoryTest,ToolSelectionPolicyTest,LabexSystemPromptTest,ProductionConfigurationTest test
```

**边界**：受控 Provider + H2 黑盒已关闭“同批先落库、正常结果按 ID 配对、approval 后 Part 状态”这一 durable batch 子切片；`AgentLoopEngine` public start/resume、用户交互、cancellation 的真实 mapper、compaction/overflow 与刷新 replay 仍未关闭，不能将本结果宣称为完整 S2 或端到端验收。
