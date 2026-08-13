# 上下文压缩、实际快照与下一次请求预测实施计划

**日期：** 2026-07-23
**状态：** 已实施并完成全量测试验证
**范围：** 自动阈值、手动压缩、实际 LLM 请求快照、NEXT_REQUEST_ESTIMATE、后端 API 与 Vue 工作区 UI

## 已交付能力

### 1. 自动压缩阈值

- 模型配置新增 `compaction_threshold_percent`，默认 `90`，允许 `70-99`。
- `ContextWindowPolicy` 结合上下文窗口、输出预留、reserve token 与阈值，选择更保守的 soft limit。
- 前端与后端都校验阈值。

### 2. 手动压缩

- 会话菜单传递当前选择的模型配置 ID，服务端复用无工具的 `CompactionAgent`。
- 模型成功时写入摘要事件；模型失败时回退为确定性 checkpoint。
- 显式指定的模型必须归属于当前用户且处于启用状态；无效配置不写入压缩事件。

### 3. 实际请求快照

- `ContextUsageEstimator` 在真正调用 provider 前保存最近一次脱敏上下文快照。
- 快照分段展示系统提示、工作区记忆、Skills 与指令、工具定义、会话消息和工具结果。
- 内容进行分段限长、总量限长与密钥形式文本脱敏。
- `GET /conversations/{conversationId}/context-preview` 返回最近实际快照。

### 4. 下一次请求预测

- 新增 `POST /conversations/{conversationId}/context-preview`，只在当前会话所有权验证通过后生成 `NEXT_REQUEST_ESTIMATE`。
- 预测使用当前会话记忆、模型、Agent 模式、打开文件以及输入框中的草稿消息。
- 草稿使用 POST body 传输，不写入 URL、不写入会话、不调用 provider。
- 快照携带 `previewSource=NEXT_REQUEST_ESTIMATE` 和元数据，说明是否已纳入草稿消息、模式和活动文件。
- 当草稿为空时，估算仅包含已知基础上下文；UI 会显式说明这一假设。

### 5. 前端

- `ContextUsageDialog` 提供“Token 用量 / 实际发送上下文 / 下一次请求预测”三个标签页。
- 预测标签自动生成首次快照，并提供“重新生成”按钮。
- UI 明确标注实际快照与预测快照的不同，不将预测冒充为已发送内容。

## 验证

- `mvn test`：486 个测试通过，7 个按现有测试配置跳过。
- 全量 `frontend/src/**/*.test.mjs`：73 个测试通过。
- `npm run build`：Vite 构建与 chunk budget 检查通过。
- 聚焦测试覆盖：阈值策略、手动压缩、实际快照脱敏、NEXT_REQUEST_ESTIMATE 的来源 / 元数据和前端安全渲染。

## 边界

- 预测快照不会消除实际快照，也不会作为会话历史落库。
- 预测会重用当前上下文装配规则，但自适应项目检索依赖草稿消息；因此草稿变更后应重新生成。
- 未运行真实 provider 的端到端 smoke test；部署后可用小会话核对 SSE、事件持久化和快照标记。

## P0 运行/验收补充

- 新增 `AgentRunStateStartupVerifier`，后端启动时强制加载状态枚举和状态机。若 JAR 内部字节码不匹配，应在启动期失败，而不是在 Agent 线程运行后才报错。
- 新增 `backend/scripts/start-release.ps1`：每次从干净构建开始启动，避免 IDE HotSwap 或旧 JAR 导致新旧 class 混用。
- 新增 `docs/agent-context-provider-smoke-test.md`：提供不包含密钥的真实 Provider 手动 smoke 验收步骤。
- 新增预测行为测试：断言下一次请求预测不调用 Provider、不创建任务、不写入对话事件。
