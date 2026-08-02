# 第 48 轮：Markdown 转义推理标签边界闭合

## 问题

用户再次观察到内部 `<think>` 协议标签出现在页面。既有第 39、40、42、45 轮已经覆盖普通标签、HTML 实体、跨 chunk、持久化 RunMessage/RunPart、SSE 重放和子 Agent，但不能据此假定所有可见形态都已被封闭。

## 根因证据

当前后端 `InternalReasoningBoundary` 和前端 `agentMarkdown` 原本能识别：

- `<think>...</think>` / `<thinking>...</thinking>`；
- 带属性、大小写混合的标签；
- `&lt;think&gt;`、数值 HTML 实体；
- 跨 Provider/SSE chunk 的不完整前缀。

但两层扫描器都不识别 Markdown 反斜杠转义的 `\<think\>`。该字符串进入 `marked` 后会被渲染为用户可见的字面量 `<think>`，因此“最终 HTML 中看到标签”的现象可以稳定复现。这不是 CSS 问题，而是 Provider 协议归一化边界遗漏。

继续做 RED 时还发现两个相邻协议问题：

1. 最终正文扫描器使用布尔 `insideReasoning`，嵌套推理块在第一个关闭标签处提前退出，后续私有文本可能泄漏；
2. `THINK.summary`、`message`、`detail` 属于用户可见元数据，但旧边界对 THINK 全字段统一执行“只删除标签、保留标签内文本”，因此 `<think>hidden</think>完成分析` 会变成 `hidden完成分析`；
3. 旧浏览器验收只在回答完成后读取最终 DOM，返回值中的 `internalReasoningProtocolHidden` 又是常量 `true`，无法证明流式阶段和持久化投影确实没有标签。

对照 `D:\opencode\opencode-dev\packages\opencode\src\session\processor.ts`，OpenCode 在 Provider 事件进入会话状态时就把 `reasoning-*` 与 `text-*` 归入不同 Part。LabexAgent 当前仍需兼容把推理协议混在 `content` 内的 OpenAI-compatible Provider，因此必须在 Provider/模型回合边界完成可靠归一化，并在前端保留只读防御，不能依赖 Markdown 渲染器“碰巧隐藏”。

## 本轮计划

1. 先增加失败回归，覆盖 Markdown 转义标签的完整文本与跨 chunk 输入；
2. 把后端扫描器升级为可识别转义标签、嵌套深度和自闭合标签的状态机；
3. 区分 reasoning 正文与用户可见摘要字段的清洗语义；
4. 同步 RunMessage/RunPart 持久化投影和前端实时/历史 reducer；
5. 让 acceptance scripted Provider 发出真实会触发旧缺陷的流；
6. 浏览器场景安装 DOM MutationObserver，并检查任务的持久化 RunMessage/RunPart；
7. 运行聚焦测试、后端全量、前端测试/构建、实际 JAR 打包与真实浏览器系统验收；
8. 审查并创建聚焦本地 Git 提交。

## 验收标准

- `\<think\>` / `\<thinking\>` 不进入 reasoning UI 的可见标签文本；
- 转义标签包裹的推理块不进入最终回答；
- 标签和转义符跨 chunk 时仍不泄漏；
- 嵌套推理块在最外层关闭前都保持不可见；
- 自闭合协议标签被删除，但不会吞掉后续正文；
- reasoning `content/delta` 保留可展示推理文本但删除协议标签；
- 可见 `summary/message/detail` 删除完整内部推理块；
- 实时 SSE、持久化事件、RunMessage/RunPart、刷新回放和最终 Markdown 渲染均不含内部协议标签；
- 浏览器验收结果由实时 DOM 探针和持久化投影检查计算，不再返回硬编码通过值。

## RED 证据

### 协议形态 RED

后端命令：

```powershell
cd D:\LabexAgent\backend
mvn '-Dtest=InternalReasoningBoundaryTest,AcceptanceScriptedProviderTest' test
```

结果：19 个测试中 4 个失败。

- `\<think\>` 被原样保留；
- 嵌套块在内层关闭后泄漏外层尾部；
- `<think/>` 被当作普通正文；
- acceptance Provider 仍只覆盖旧的 HTML 实体 fixture。

前端命令：

```powershell
cd D:\LabexAgent\frontend
node --test src/utils/agentMarkdown.test.mjs
```

结果：11 个测试中 2 个失败，分别证明 Markdown 转义标签和嵌套块可绕过前端防御。

### 字段语义 RED

实时与历史 reducer 回归最初都得到 `hiddenAnalysis complete`，而期望仅为 `Analysis complete`。这证明可见 summary 不能复用 reasoning 正文的 tag-only 投影。

## 实现记录

1. `InternalReasoningBoundary` 支持 Markdown 反斜杠转义、原始/HTML 实体、带属性、自闭合和跨 chunk 协议标签；
2. `VisibleStreamFilter` 从布尔状态改为嵌套深度，最外层关闭前都不会把 reasoning 投影成正文；
3. 流末尾只在确实是协议前缀时丢弃残片，普通单个反斜杠仍能在 flush 时保留；
4. 权威 SSE/event 边界按字段语义清洗：`content/delta` 作为 reasoning 正文只去标签，`summary/message/detail` 作为可见元数据删除完整推理块；
5. `AgentRunMessageService`、`AgentRunPartService` 使用相同字段语义，避免实时正确但刷新回放重新泄漏；
6. 前端 `agentMarkdown` 同步扫描能力，`useAgentEventTimeline` 与 `agentHistoryReducer` 对 reasoning summary 使用完整块清洗；
7. acceptance scripted Provider 改为发出跨 chunk 的 `\<think\>`、嵌套 `<thinking>` 与自定义属性 fixture；
8. 浏览器验收在每个新文档安装 MutationObserver，持续检查聊天区域的 `innerText` 与 `innerHTML`；
9. 验收同时读取 reasoning 任务的持久化 `runMessages` / `parts`，递归检查协议标签；
10. `internalReasoningProtocolHidden` 由实时、刷新和持久化三项结果共同计算，不再硬编码为 `true`。

## 验证结果

### 聚焦回归

- 后端 reasoning/SSE/RunMessage/RunPart 组合：47/47 通过；最终新增持久化字段语义回归后，相关服务复核 17/17 通过；
- 前端 Markdown、实时 reducer、历史 reducer：51/51 通过。

### 全量与构建

- 后端 `mvn test`：879 个测试，0 失败，0 错误，8 跳过；
- 前端 `npm test`：188/188 通过；
- 前端 `npm run test:acceptance:unit`：10/10 通过；
- 前端 `npm run build`：通过；
- bundle 预算：`CloudWorkspace` 1,460,508 / 1,500,000，`index` 1,259,534 / 1,300,000，`TerminalPanel` 380,367 / 400,000；
- 实际 JAR 已重新打包：`D:\LabexAgent\backend\target\labex-agent-backend-1.0.0.jar`。

### 真实浏览器系统验收

命令：

```powershell
cd D:\LabexAgent
.\scripts\acceptance\browser-runtime.ps1 `
  -BackendPort 18080 `
  -FrontendPort 13000 `
  -CdpPort 19222 `
  -TimeoutSeconds 180 `
  -RestartBackendForAcceptance
```

结果：通过。

- run ID：`2eb9a2f7d14e4452a0ebc9e5f3f5f16d`；
- reasoning 边界任务：`1826`；
- `internalReasoningProtocolHidden=true`，由实时 DOM、刷新回放、持久化 RunMessage/RunPart 三层检查共同得出；
- conversation isolation、刷新去重、审批恢复、多工具审批、持久 compaction、Provider 中断、两轮后端重启恢复、静态上下文阻塞和完成证据场景全部通过；
- 浏览器 console errors：0；network errors：0；
- 验收日志：`C:\Users\35475\AppData\Local\Temp\labex-agent-browser-runtime-b3b0d70c85c84b84bb68a8bb03f4f2a5`；
- 13000、18080、19222 端口均已释放。

## 风险与后续

- 本轮修复的是兼容 OpenAI-compatible 文本协议所需的归一化适配层；长期目标仍应像 OpenCode 一样，让 Provider 直接产出有身份的 reasoning/text Part，字符串标签扫描只保留为兼容防线；
- 仍需继续总目标中的 waiting 状态非终态协议审计，以及“模型原始思考是否应长期持久化”的契约收敛；
- 未改动、未暂存用户原有的 `application-acceptance.yml` 行尾差异和其他未跟踪文档/临时目录。