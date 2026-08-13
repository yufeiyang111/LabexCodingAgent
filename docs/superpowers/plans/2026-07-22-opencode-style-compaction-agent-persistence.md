# OpenCode 风格上下文管理：专用压缩 Agent 与持久化恢复实施计划

**日期：** 2026-07-22  
**范围：** `backend` Agent 运行时、会话事件、模型配置 API、模型配置界面  
**前置：** 已完成软阈值预检、回合感知工具结果 pruning、确定性 checkpoint 回退。

## 目标

在不让压缩流程获得工具、写文件或执行命令能力的前提下，为接近模型输入窗口的 Agent 会话增加：

1. 可选的专用 LLM 压缩模型（默认沿用当前模型）；
2. 严格 JSON 输出验证和敏感值遮蔽；
3. 以 `t_agent_message` 为事件链的压缩开始、摘要、成功、失败、prune 记录；
4. 断线/恢复时使用“最近一次压缩摘要 + 摘要后的尾部事件”重建记忆；
5. 模型压缩失败时继续使用现有确定性 checkpoint，保证不依赖单一 provider。

## 非目标

- 不把压缩 Agent 暴露为可调用工具；
- 不允许其访问文件、终端、MCP、网络、权限或 diff 服务；
- 不新增独立数据库表（现有 `t_agent_message` 的类型、内容和事件数据字段足够）；
- 不删除现有 `AgentConversationService` 的手动/自动摘要能力。

## 设计

### 1. 模型配置

在 `t_agent_model_config` 增加可空 `compaction_model_config_id`：

- `NULL`：压缩 Agent 使用当前 Agent 模型；
- 非空：必须是同一用户、状态可用的模型配置；
- 该字段仅指定摘要调用模型，不会改变当前 Agent 的工具模型；
- 创建/更新配置时校验所有权和状态，避免跨用户引用。

### 2. 专用 Compaction Agent

新增运行时组件，输入为受限历史投影、任务、运行时状态和当前尾部上下文，调用 `LlmProvider.chatWithTools(..., List.of(), ...)`：

- 不传工具定义，且不使用 Agent 循环、ToolRegistry、MCP 或工作区服务；
- 强制低温、较小输出上限，并取消 prompt cache key；
- 期待 JSON：`summary`、`facts`、`nextActions`、`openRisks`、`files`、`verification`；
- 解析后限制字段数量/长度并移除敏感值；
- 失败、非 JSON、字段缺失或摘要无法缩短时返回失败，不覆盖上下文。

### 3. 预检与回退顺序

`AgentLoopEngine` 进入下一次 LLM 请求前：

1. 未超过软阈值：不操作；
2. 已启用且可 prune：清理旧的可重取工具输出，发 `CONTEXT_PRUNED`；
3. 仍超阈值：发 `COMPACTION_STARTED`，调用专用 Compaction Agent；
4. 成功：替换旧运行时消息为 `<conversation-checkpoint version="3" source="model">` + 受保护尾部；持久化 `COMPACTION_SUMMARY` 与 `COMPACTION_COMPLETED`；
5. 失败：持久化 `COMPACTION_FAILED`，执行现有确定性 checkpoint；若确定性 checkpoint 也无法缩短则保留原上下文，让 provider 的现有溢出处理继续工作。

### 4. 持久化与恢复

`AgentConversationService` 新增专用摘要写入方法：

- 在一条 `COMPACTION_SUMMARY` 事件中保存已验证的 checkpoint；
- 同时更新 `t_agent_conversation.summary` 和 `compacted_at`；
- `buildMemoryContext` 优先查找最新 `COMPACTION_SUMMARY`，再仅附加该记录之后的有限尾部事件；没有摘要时保留现有摘要 + 最近事件行为。

所有状态事件经现有 SSE/会话事件路径写入，避免内存中存在而恢复后丢失。

## 实施步骤

1. 扩展模型配置实体、schema、增量迁移、服务、Controller DTO 和 Vue 表单；添加所有权校验测试。
2. 先为 `CompactionAgent` 写单测：无工具调用、有效 JSON、无效输出、敏感信息遮蔽、指定配置回退。
3. 实现 Compaction Agent、严格验证器和模型 checkpoint 写入器；为确定性/模型 checkpoint 共用历史替换逻辑。
4. 扩展会话持久化与恢复，增加相关单测。
5. 在 `AgentLoopEngine` 集成预检、SSE 事件和失败回退；添加针对性运行时测试。
6. 运行后端 focused tests、全量 `mvn test`、`mvn -DskipTests compile`、前端 `npm run build` 和 `git diff --check`。

## 验收

- 任何模型摘要调用都传空工具集，且不会触发文件/终端/MCP；
- 失败时 Agent 仍能落到确定性 checkpoint；
- 成功时数据库含完整事件链，恢复上下文使用最近摘要与后续尾部；
- 用户可选择“沿用当前模型”或本人的另一模型配置；
- 没有真实 provider 凭证时，单测覆盖结构、验证、失败和回退路径；真实调用另行人工 smoke test。
