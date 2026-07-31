# 第 11 轮：多工具交互恢复、会话刷新隔离与租约竞争收敛

- 日期：2026-07-31
- 分支：`codex/agent-tool-reliability`
- 对应计划：`docs/superpowers/plans/2026-07-29-agent-runtime-state-convergence.md` 的任务三、任务六、任务七、任务九
- 状态：完成

## 本轮计划

1. 固定同一 assistant turn 包含多个 tool call、其中一个进入审批等待时的 Provider 协议和恢复行为。
2. 保证审批、网络授权和用户提问始终保存模型给出的原始 `toolCallId`，不得在恢复阶段生成新身份。
3. 为被审批暂停而未执行的 companion tool call 生成持久、可投影、顺序稳定的明确结果，避免孤立 `tool_call`。
4. 修复刷新后的会话选择隔离和后端会话排序不确定性。
5. 修复 execution lease 与 retry scheduler 并发领取同一 task 的竞争窗口。
6. 用真实 Chromium、持久数据库和跨 JVM 重启证明审批恢复继续原 task，而不是只依赖单元测试。

## 红灯与现场失败

### 1. 刷新启动选择忽略持久会话归属

新增前端回归测试后，启动解析器最初不能按项目恢复已选择会话，而是默认选择响应列表第一项；同时前端对后端返回结果重新排序，会覆盖服务端定义的顺序。

后端排序测试也固定了原有问题：只按 `update_time` 排序时，同一时间戳会话的顺序不稳定，刷新后可能进入错误历史会话。

### 2. 同一 owner 可以重复取得尚未过期的 execution lease

lease 回归测试最初证明：当前 owner 再次领取时会刷新已有 lease，而不是拒绝第二次执行。retry scheduler 因此可能在旧执行仍活跃时启动同 task 的第二条恢复链路。

### 3. 多 tool call 在审批暂停后缺少完整 Provider 结果

原逻辑只恢复触发审批的那一个调用。assistant turn 中其余已持久化的 tool call 没有对应 `role=tool` 结果，导致重启或下一次 Provider 投影时存在孤立调用、顺序不完整或无法解释的 pending Part。

### 4. 第一次真实浏览器验收在 `waiting_workspace` 之后得到错误结果

现场 task 的 request objective 和 durable transcript 都包含 `[acceptance:permission-batch]`，但当前验收后端日志没有对应 Provider 调用，任务却被输出了旧版本的通用结果。

进程审计找到两个共享同一 acceptance 数据库、仍运行 scheduler 的旧 JVM：

- PID `62896`，监听 `8080`，启动时间 `2026-07-30 20:36:54`；
- PID `61744`，监听 `18080`，启动时间 `2026-07-31 03:33:17`。

旧 JVM 抢领了新后端写入的等待任务，并使用旧 class 完成执行。停止这两个旧 acceptance JVM 后，同一系统场景进入正确的多工具审批路径。该失败不是 request 丢失，而是混合版本 worker 缺少硬隔离。

### 5. 刷新后第一次模拟 Enter 偶发丢失

清理旧 JVM 后，浏览器验收继续到后续静态上下文场景，但刷新后的第一次合成 Enter 没有触发 Vue 提交。验收脚本增加受控 fallback：先发送 Enter，短暂等待后若输入仍未被 Vue 清空，则向真实提交按钮发送 pointer/mouse/click 事件。脚本仍校验输入被清空且用户消息进入时间线，避免把直接调用内部函数当作 UI 验收。

## 实施内容

### 1. 持久化多工具交互结果

`AgentRunTranscriptService` 新增已解决交互结果投影：

- 使用 interaction 保存的原始 `toolCallId` 定位实际调用；
- 实际审批调用保存真实批准、拒绝或回答结果；
- 同一 assistant batch 中未执行的 companion 调用保存明确的 skipped/interrupted/error/completed 结果；
- 结果保持原始 Part 顺序和工具名；
- 保证每个 Provider `tool_call` 都存在可重建的 `role=tool` 对应结果。

`AgentLoopEngine` 统一消费这些 durable results，不再只为当前 interaction 临时拼一个结果。

### 2. 保留原始交互身份

`PermissionService`、`NetworkAccessService` 和 `AgentInteractionService` 在创建等待交互时保存原始 `toolCallId`。审批、网络授权、问题回答和重启恢复均继续原 task/epoch/tool call，不生成新的工具调用身份。

### 3. execution lease 与 retry scheduler

- 任何尚未过期的 active lease 都拒绝再次领取，包括相同 owner；
- scheduler 在旧 execution lease 仍活跃时跳过 retry；
- retry 恢复先进入 `recovering`，实际取得执行租约后才进入 `running`；
- 生命周期迁移保留 actor、reason、epoch 和幂等边界。

### 4. 会话刷新隔离

- 前端使用 project-scoped `sessionStorage` 保存最近选择的 conversation；
- 启动时先解析当前项目持久选择，再决定是否加载默认会话；
- 新会话意图会使旧异步选择失效；
- 前端保留后端响应顺序，不再自行进行可能不稳定的二次排序；
- 后端使用 `update_time DESC, create_time DESC, conversation_id DESC` 提供确定性顺序。

### 5. 验收场景与可观测性

acceptance Provider 新增 `[acceptance:permission-batch]`：

1. `read_file`，`toolCallId=acceptance-permission-batch-read`，读取 `.env` 并进入审批；
2. `list_files`，`toolCallId=acceptance-permission-batch-list`，作为同 batch companion 调用。

验收日志记录 Provider message 数量和 permission batch marker；浏览器脚本校验两个 tool call 的顺序、身份和 durable Provider 结果。

## 验证记录

### 后端完整测试

```powershell
cd D:\LabexAgent\backend
mvn -q test
```

结果：通过。

### 前端完整测试、构建与 acceptance unit

```powershell
cd D:\LabexAgent\frontend
npm.cmd test
npm.cmd run build
npm.cmd run test:acceptance:unit
```

结果：

- 前端测试：`160/160` 通过；
- Vite 生产构建：通过；
- `CloudWorkspace` bundle：`1,449,183 / 1,500,000` bytes，通过预算；
- `index` bundle：`1,259,534 / 1,300,000` bytes，通过预算；
- `TerminalPanel` bundle：`380,367 / 400,000` bytes，通过预算；
- acceptance unit：`9/9` 通过。

### 工作树完整性

```powershell
cd D:\LabexAgent
git diff --check
```

结果：通过；临时诊断测试 `TemporaryTask909DiagnosticTest.java` 已确认不存在。

### 普通真实浏览器系统验收

隔离拓扑：

- Vite：`127.0.0.1:13002`；
- Spring Boot：`127.0.0.1:18081`；
- Chromium：真实桌面 viewport `1440x900`。

验收结果：

- `desktopLayout=true`；
- `conversationIsolation=true`；
- `refreshReplayDeduplicated=true`；
- `questionReplyComponent=true`；
- `permissionApprovalRefreshRecovery=true`；
- `multiToolPermissionBatchProtocolComplete=true`；
- durable Provider Message 数量 `3`；
- durable Provider Part 数量 `1`；
- `durableCompaction=true`，epoch `1`；
- `providerStreamInterruptionHandled=true`；
- `staticContextBlockerCard=true`；
- `completionEvidenceCard=true`；
- `unverifiedCompletionBlocked=true`；
- console error `0`；
- network error `0`。

### 多工具审批中真实 JVM 重启

验收目录：

`D:\LabexAgent\.codex-tmp\iteration11-cross-jvm-multitool-20260731-1630`

持久身份：

- projectId：`165`；
- taskId：`934`；
- conversationId：`02d327e5-9f88-4eff-958d-05e4550cf12b`；
- interactionId：`e200a5a5-7050-4950-9a22-d6866732dcfc`；
- toolCallId：`acceptance-permission-batch-read`；
- companion toolCallId：`acceptance-permission-batch-list`。

过程与结果：

1. 浏览器在审批卡片处写出 `ready.json` 并等待 `continue.signal`；
2. 停止旧隔离 JVM；
3. 启动新 JVM PID `65396`，启动时间 `2026-07-31 16:33:56`，classpath 指向 `D:\LabexAgent\backend\target\classes`；
4. 写入 `continue.signal`；
5. 页面恢复 durable interaction 并批准；
6. 原 task 完成，没有创建新的对话任务；
7. `restartInteractionVerified=true`；
8. `multiToolPermissionBatchProtocolComplete=true`；
9. console error `0`，network error `0`。

`restartProjectionVerified=false` 在该模式下是预期值：本轮运行的是 interaction handoff，而完整 projection restart 已在第 9 轮独立验收通过。

## 源码/测试证据与现场证据边界

- 单元测试证明协议、排序、租约和 reducer 的确定性行为；
- build 只证明前端可生产构建，不证明审批恢复；
- 普通 Chromium 验收证明页面刷新、真实输入、审批组件和事件回放；
- 跨 JVM handoff 证明 durable interaction 在旧进程退出后仍能继续原 task；
- 本轮没有把 mock、编译成功或提交 acknowledgement 当作系统完成证据。

## 保留的原工作区改动

以下内容不属于本轮，不纳入提交：

- `backend/src/main/resources/application-acceptance.yml`；
- `.codex-tmp/`；
- `docs/agent-context-provider-smoke-test.md`；
- `docs/coding-agent-engineering-roadmap.md`；
- `docs/coding-agent-industrialization/`；
- 既有未跟踪的 `docs/superpowers/plans/` 和 `docs/superpowers/specs/` 文档。

## 剩余风险与下一轮

1. 混合版本 acceptance JVM 仍可能共享数据库并抢领任务。下一轮应增加 runtime compatibility fencing 或 acceptance 启动 preflight，不能只依靠人工停止旧进程。
2. 旧内存 `msgs`、重复 conversation summary 和旧前端 `toolCalls` 权威路径仍需完成 shadow compare 后删除。
3. 继续补齐命令未启动、工具半截结果、compaction 失败、未知模型窗口、lease takeover 和 scheduler 重复领取故障注入。
4. 继续按风险合并重复测试，测试数量不能替代真实系统证据。
