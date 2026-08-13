# OpenCode 风格异步压缩与工具可观测性实施计划

**目标：** 将 LabexAgent 的上下文压缩、工具执行、超时和工具结果投影改造成事件驱动、可观察、可取消的架构；保留已有安全裁剪与快照机制。

## 已确认基线

- `AgentLoopEngine` 已在日志和 run log 中记录工具总耗时、delegate、前后快照、diff、post-edit、context 和 metrics 分项，但这些数据没有进入 SSE/UI。
- 工具调用仍是同步 `t.execute(ctx,args)`，没有统一工具级 watchdog。
- 手动压缩会持久化 `COMPACTION_*` 事件，但 HTTP 接口同步等待模型摘要完成；前端已补本地状态卡片，仍不是异步 operation。
- 当前 task SSE 仅按 `taskId` 订阅；需为手动压缩建立可订阅 operation/task 边界，不能只依赖同步 HTTP。
- Provider 首包超时存在，但总请求、chunk 间隔、工具执行超时尚未统一向用户配置与事件层暴露。

## 阶段 1：工具生命周期事件和 UI 耗时（P0）

1. 为每次工具调用分配稳定 `toolCallId`，在 `TOOL_CALL`、`TOOL_STARTED`、`TOOL_PHASE_CHANGED`、`OBSERVE`/`TOOL_COMPLETED`/`TOOL_FAILED` 中携带。
2. 将现有 `execTool` 已测得的 timing map 发送为持久化 task 事件，不重复计时。
3. 前端 reducer 按 `toolCallId` 更新 `ToolCallCard`；展示运行中总耗时、当前阶段、完成后的分项耗时和失败阶段。
4. 为历史回放、SSE 重连、缺少新事件的旧记录提供兼容降级。

## 阶段 2：手动压缩异步 operation（P0）

1. 新建受 ownership 保护的 `AgentContextOperation` 持久化记录，类型为 `MANUAL_COMPACTION`，包含 operationId、conversationId、task-like sequence、状态、取消请求、开始/结束时间和错误摘要。
2. `POST /compact` 创建 operation 并立即返回 operationId；后台执行 CompactionAgent。
3. 复用 task-event 的持久化 sequence/SSE 订阅模式，或抽取 conversation-operation subscription，发布同名 `COMPACTION_*` 事件。
4. 前端本地状态卡片改为由 operation 事件驱动，可显示“排队/运行/模型摘要/确定性回退/完成/失败/取消”。

## 阶段 3：分层超时与取消（P1）

1. 模型配置增加并校验 provider connect/header/overall/chunk timeout，避免把首包慢混同于工具超时。
2. 定义工具类别预算：轻量读取/搜索、Git/diff、LSP、MCP/网络、终端/测试；预算超过时请求 CancellationToken 并返回 `TOOL_TIMED_OUT`。
3. 不覆盖工具自身更严格的 timeout；终端命令继续保留显式用户 timeout，但上限与 UI 显示一致。

## 阶段 4：工具结果上下文投影（P1）

1. 保留完整用户可见 tool output；新增输入模型的受限 projection，含退出码、文件引用、头尾片段、结构化摘要和 truncation 标志。
2. 将 projection token/字符预算作为可测配置，和 compaction checkpoint 共用红线策略。
3. 在工具卡片显示“完整输出已保留；模型上下文使用摘要”的透明提示。

## 验证

- 后端单测：事件 sequence、ownership、operation 状态机、取消、timeout 分类、projection 截断。
- 前端 Node 测试：事件归并、耗时显示、历史回放、operation 失败/回退。
- `mvn test`、`npm test`、`npm run build`；Windows JAR 或 Temp 文件锁单独记录，不以绕过方式处理。
