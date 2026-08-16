# LabexAgent 工具收敛与 Harness 控制面重构规格

**状态：** 已获用户在 2026-08-15 对话中确认，开始分阶段实施
**适用仓库：** `D:\LabexAgent`
**OpenCode 本地参考：** `D:\opencode\opencode-dev`，版本 `1.17.4`
**关联研究：** `docs/coding-agent-industrialization/opencode-harness-semantic-controls-review-2026-08-15.md`

## 1. 问题

当前 build 模式将 29 个静态模型工具同时注入；其中大量工具是 Harness 的计划、上下文、验证或预览控制面，而非不可替代的原子执行能力。`create_plan` 与未完成计划又会阻断 final，`run_tests` 与 `shell` 重叠，最终造成简单任务被升级为计划、测试、安装依赖、预览的错误工作流。

## 2. 参考的 OpenCode 形态

1. 普通进度采用可选 `todowrite`，写入 session Todo 持久化并发布 `todo.updated`；Todo 不决定任务能否完成。
2. 普通工程行为通过 `shell` 执行；没有 `run_tests`、`repo_clone`、`start_preview` 等强制工作流工具。
3. `plan_exit` 仅用于用户显式进入的实验性 CLI Plan Mode，不是默认 build 流程。
4. build agent 默认 `* -> allow`，安装、构建、测试、Git 和本地服务不会因为普通工程行为被审批阻断；外部目录和 `.env` 等仍有单独规则。

## 3. 目标架构

### 3.1 模型可见的原子工具

默认 Coding Agent 只应获得任务需要的原子能力：

- 文件：`read_file`、`glob`、`grep`、一组编辑能力；
- 执行：唯一的 `shell`；
- 交互：`question`、可选 `todo_write`；
- 可选能力：Web、LSP、图片、MCP、子代理仅在当前任务或运行时能力相关时挂载。

`run_tests`、`execute_code`、`repo_clone`、计划创建、上下文备注、预览生命周期、项目摘要/RAG、配置提案等不应作为默认模型工具。

### 3.2 Harness 所有的能力

- 计划/Todo 的 durable 存储、事件和 UI 投影；
- 上下文摘要、repo digest、检索和 compaction；
- shell 结果的 verification evidence 分类；
- 受控后台进程的登记、回收与 preview evidence；
- 循环保护、预算、恢复、finalization。

### 3.3 Todo 语义

保留单个可选 `todo_write`，但它只是 UI 进度投影：

- 没有 Todo 不阻断任何工具调用或 final；
- 未完成 Todo 不否决真实已完成的任务；
- Todo 状态不能覆盖 durable Tool Part、shell exit code 或 verification evidence；
- 简单任务不要求写 Todo。

### 3.4 普通工程命令

普通 `shell` 默认可以运行安装依赖、clone/fetch、build/test/lint、启动本地开发服务和普通 Git 操作。Harness 记录结果并投影 evidence，但不以 workflow 审批限制这些能力。

只保留极窄的、可配置的 destructive guard：删除、递归删除、`git reset --hard`、`git checkout -- <file>`、`git restore`、`git clean` 和 workspace 外写入。

## 4. 分阶段实施

### Phase A：解除错误工作流门槛（本轮优先）

1. 移除 `create_plan` 对默认 system prompt、build schema 和 finalization 的强制依赖；
2. 保留 `todo_write`，将其文案和行为收敛为可选进度同步；
3. finalization 不再因为 Todo/Plan 未完成而返回模型继续执行；
4. `run_tests` 不再作为模型可见默认工具；shell 执行结果负责验证投影；
5. 建立最小默认工具 profile，先隐藏明显重叠的 workflow/control-plane 工具，同时保留 durable replay 兼容。

### Phase B：按任务动态挂载能力

1. 持久化或可确定性重放的 Task Intent/Tool Profile；
2. 对代码修改、检索/下载、诊断、聊天等任务选择不同 schema；
3. Web/LSP/Image/MCP/Task 仅在 capability 与任务相关时加载；
4. 为模型能力升级提供可恢复的 profile 变更事件，而不是重新全量暴露工具。

### Phase C：Harness 内部服务替代专用工具

1. repo digest/RAG 由 context assembler 按需注入；
2. preview 生命周期由进程监督服务处理；
3. shell verification projector 从命令与结果写 durable evidence；
4. plan mode 只在用户显式选择时作为独立 UI/agent mode 提供。

## 5. 验收

- 简单下载任务不得自动要求 `create_plan`、`run_tests`、preview；
- Todo 缺失或未完成时，真实完成任务可正常 final；
- `npm install`、`git clone`、`npm test` 通过 `shell` 可执行，不因专用审批失败；
- build schema 不含 `create_plan`、`run_tests` 等收敛目标；
- 回放旧任务仍可解析历史 `create_plan` / `run_tests` Tool Part，不能破坏 durable transcript；
- 前端只根据 durable Plan/Progress 事件显示侧边栏状态。

## 6. 不做的事

- 不在本轮重写所有 AgentLoopEngine；
- 不为依赖安装构建新的复杂 allowlist/sandbox；
- 不删除历史 Tool Part 的兼容执行或回放逻辑；
- 不机械照搬 OpenCode 的单进程、内存 pending permission 设计。
