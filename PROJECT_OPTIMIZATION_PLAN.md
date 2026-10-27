# LabexAgent 项目优化路线图

> 文档状态：待执行
> 审计基线：2026-10-25，`main` 分支，提交 `3fc47e8`
> 适用范围：发布链路、数据库迁移、Agent 运行时、前端工作区、同步模块、工程文档与仓库卫生
> 执行原则：小步、可回滚、每步有验收；先修正确性与发布安全，再做结构收敛

---

## 0. 先看结论

这个项目当前的主要问题不是功能少，而是**复杂度已经超过了现有门禁和边界所能保护的范围**：

- 运行核心 `AgentLoopEngine.java` 约 5,896 行，承担模型轮次、工具、审批、SSE、恢复等多重职责。
- 前端 `CloudWorkspace.vue` 约 4,532 行，承担会话、文件、diff、终端、预览、计费、布局等多重职责。
- CI 后端只编译、不跑测试；生产 profile 仍会执行 `schema.sql` / `data.sql`。
- 同步模块已有大量实现和测试，但明确未接 UI、未接真实目录，且仍有多个 P0 未修项。
- 可共享工程文档在本地存在，但 `docs/` 被 `.gitignore` 整体忽略，换机器会丢失。
- 当前本地完整 preflight 有两项环境/依赖失败，发布验证不可稳定复现。

因此执行顺序应为：

1. 先保证“坏版本进不了生产”。
2. 再保证“生产数据库只走受控迁移”。
3. 再补部署后的健康检查与回滚。
4. 然后冻结未完成同步模块的边界。
5. 最后才拆运行核心和前端大组件。

---

## 1. 当前基线证据

| 项目 | 当前事实 | 证据 |
|---|---|---|
| Git 状态 | `main` 领先 `origin/main` 24 个提交 | `git status --short --branch` |
| 未提交改动 | 18 个路径，其中 12 个已跟踪文件，约 +429/-91 | `git status --short`、`git diff --shortstat` |
| 后端测试规模 | 425 个 Java 测试文件 | `backend/src/test/java/**/*.java` |
| 前端测试规模 | 86 个 `.test.mjs` 文件 | `frontend/src/**/*.test.mjs` |
| 运行核心体量 | `AgentLoopEngine.java` 5,896 行 | 文件行数 |
| 前端主视图体量 | `CloudWorkspace.vue` 4,532 行 | 文件行数 |
| CI 后端门禁 | `mvn clean package -DskipTests` | `.github/workflows/deploy.yml` |
| 生产 SQL 初始化 | 未覆盖 `spring.sql.init.mode`，默认 `always` | `application.yml`、`application-production.yml` |
| 部署健康检查 | 仅 `docker compose ps`，无应用级 smoke test | `.github/workflows/deploy.yml` |
| 文档版本化 | `docs/` 被 `.gitignore` 忽略，`git ls-files docs/**` 为 0 | `.gitignore:74` |
| 本地 preflight | 前端构建、后端编译两项失败 | `node scripts/preflight-release.mjs` |
| 静态门禁 | `check-file-lines` 通过；`git diff --check` 通过 | 实际命令 |
| Git 自检 | `git fsck --no-dangling` 退出码 0 | 实际命令 |

---

## 2. 阶段总览

| 阶段 | 目标 | 完成标志 |
|---|---|---|
| P0-A | 发布门禁可信 | CI 和本地都能稳定跑后端测试、前端构建、迁移校验 |
| P0-B | 生产数据库受控 | 生产不再执行全量 `schema.sql` / `data.sql`，只走迁移台账 |
| P0-C | 部署可观测、可回滚 | 部署后自动健康检查，失败可自动或半自动回滚 |
| P1-A | 未完成模块冻结 | 同步模块在未修项清零前不进入真实 UI/目录 |
| P1-B | 运行核心收敛 | `AgentLoopEngine` 拆成明确协作边界，主循环变薄 |
| P1-C | 前端工作区收敛 | `CloudWorkspace.vue` 拆成 feature container + composable/store |
| P2-A | 接口与 DTO 收敛 | Controller 变薄，`Map<String,Object>` 逐步替换为强类型 DTO |
| P2-B | 文档与仓库卫生 | 工程文档纳入版本控制，Git 残留与产物隔离 |

---

## 3. P0-A：让发布门禁真正可信

### Step 1：在 CI 执行后端测试

**要做什么**
- 在 `.github/workflows/deploy.yml` 增加后端测试步骤。
- 先跑高风险测试集合，稳定后扩展为全量。
- 本地 `scripts/preflight-release.mjs` 增加同一判据。
- 保留 `test-compile` 作为快速失败，但不要把它当测试通过。

**建议命令**
```bash
cd backend
mvn -s settings-local.xml test
```

如果全量耗时过长，第一阶段至少覆盖：
```bash
mvn -s settings-local.xml -Dtest=AgentRunLifecycleServiceTest,AgentRunTranscriptServiceTest,AgentRunPartServiceTest,AgentRunRecoveryServiceTest,AgentRunInteractionDispatchClaimTest,CommandApprovalOrchestratorTest,AgentSyncFeedServiceTest test
```

**验收标准**
- CI 日志明确出现测试执行和通过数量。
- 故意让一个高风险测试失败，CI 必须在构建镜像前失败。
- 本地 preflight 使用同一命令并通过。

**做完会发生什么**
- 运行状态机、审批、恢复、transcript 的回归会在发布前被拦住。
- “能编译”不再被误当成“逻辑正确”。
- 发布失败会更早发生，修复成本更低。

**风险与回滚**
- 风险：全量测试可能暴露既有不稳定测试。
- 回滚：先保留新增步骤但标记为 `continue-on-error` 仅用于观察，下一提交恢复为阻断门禁；不要长期保留非阻断状态。

### Step 2：修复本地 preflight 的环境可复现性

**要做什么**
- 去掉 `scripts/preflight-release.mjs` 中硬编码的 `D:/jdk21/jdk-21.0.10+7` 和 `D:/apache-maven-3.9.10`。
- 改为按以下顺序解析：
  1. 环境变量 `JAVA_HOME`、`MAVEN_HOME`
  2. 仓库脚本或 `mvn -v` 自动探测
  3. 明确报错并给出安装/配置命令
- 前端构建统一先执行 `npm ci`，再 `npm run build`。
- 对 Rollup 原生模块缺失给出可操作的修复提示。

**验收标准**
- 在新机器或干净环境执行 `node scripts/preflight-release.mjs` 能通过，或给出明确缺失项。
- 不再出现“路径存在性失败但没有修复指引”。
- `npm ci && npm run build` 可重复通过。

**做完会发生什么**
- 本地门禁与 CI 判据一致。
- 换机器、换用户、换 JDK 路径时不会出现假失败。
- 开发者能在 push 前完成真实验证。

**风险与回滚**
- 风险：自动探测可能选到错误 JDK。
- 回滚：保留显式环境变量优先，探测失败时只报错不猜测。

### Step 3：为关键门禁增加负向自检

**要做什么**
- 增加脚本测试，验证门禁本身会失败：
  - 故意放一个未定义引用，`check:undef` 必须失败。
  - 故意放一个超过 400 行的新模块文件，`check-file-lines` 必须失败。
  - 故意放一个命名不合规的迁移，preflight 必须失败。
- 修复“目录不存在时打印通过”的假绿灯模式。

**验收标准**
- 每个负向用例都有自动化测试或可重复脚本。
- CI 中至少有一个“门禁自检”步骤。

**做完会发生什么**
- 门禁不会因为路径写错、目录为空或脚本吞异常而静默通过。
- 团队可以相信“绿色”结果。

---
## 4. P0-B：生产数据库只走受控迁移

### Step 4：生产 profile 禁用全量 SQL 初始化

**要做什么**
- 在 `backend/src/main/resources/application-production.yml` 增加：
```yaml
spring:
  sql:
    init:
      mode: never
```
- 开发/本地 profile 保持自动初始化。
- 生产结构变更只走 `deploy/linux/migrations/upgrade-YYYYMMDD.sql`。
- 同步更新 `backend/src/main/resources/sql/schema.sql` 作为开发基线，但不再让生产启动执行它。

**验收标准**
- 启动 production profile 的测试断言 `spring.sql.init.mode=never`。
- 生产启动日志不再出现执行 `schema.sql` / `data.sql`。
- 新库初始化与旧库升级各有明确路径，且都有文档。

**做完会发生什么**
- 生产启动不会因为全量 schema 脚本产生意外结构变更或数据覆盖。
- 数据库变更可追溯、可重放、可审计。
- 部署与发布手册的“增量迁移铁律”真正落地。

**风险与回滚**
- 风险：新生产库若没有预先建表会启动失败。
- 回滚：部署前先确认新库初始化 SOP；回滚只恢复配置，不直接改生产数据。

### Step 5：迁移链路补齐“应用前校验”和“失败可见”

**要做什么**
- 在 `apply-migrations.sh` 中增加：
  - 迁移文件命名、内容 SHA-256、台账一致性校验。
  - 每个迁移执行前后的日志边界。
  - 失败时输出当前迁移名、已应用台账、下一待执行项。
- CI 增加迁移脚本幂等性测试。
- 对已存在但未入台账的历史迁移，明确“重跑一次”的安全策略。

**验收标准**
- 同一迁移重复执行零报错、零数据破坏。
- 失败时能在日志中直接定位文件与 SQL 段。
- CI 能拦住命名不合规、空文件、重复内容冲突。

**做完会发生什么**
- 数据库发布从“靠人工小心”变成“可验证流程”。
- 故障定位时间缩短，重复发布更安全。

### Step 6：把生产配置校验纳入测试

**要做什么**
- 增加 `ProductionProfileConfigurationTest`，覆盖：
  - SQL 初始化关闭。
  - CORS/WebSocket 来源必须显式 HTTPS。
  - JWT、数据库、Secret Store 主密钥缺失时启动失败。
  - 不允许生产使用 `D:/...` 路径或 full access。
- 对 `application-production.yml` 做字段存在性测试。

**验收标准**
- 生产配置缺失时测试失败，而不是部署后才暴露。
- 测试不依赖真实生产凭据。

**做完会发生什么**
- 生产配置漂移会在 CI 暴露。
- 发布前能区分“代码正确”和“生产配置正确”。

---

## 5. P0-C：部署后健康检查与回滚

### Step 7：部署后等待应用级健康检查

**要做什么**
- 在 `.github/workflows/deploy.yml` 的重启步骤后增加：
  - 轮询 `/api/actuator/health` 或项目现有健康端点。
  - 超时后输出容器日志、最近镜像标签、迁移台账。
  - 至少验证一个匿名接口、一个受保护接口和一个静态资源。
- 健康检查失败时 CI 必须失败，不能只打印 `docker compose ps`。

**验收标准**
- 故意让后端启动失败，CI 必须在部署步骤失败。
- 健康检查通过后才打印“发布完成”。
- 失败日志包含足够定位信息但不泄露密钥。

**做完会发生什么**
- “容器起来了”升级为“应用真的可用”。
- 发布失败会立即暴露，而不是等用户访问时才发现。

### Step 8：建立镜像与前端版本回滚

**要做什么**
- 每次发布保留上一稳定镜像标签。
- 前端静态资源按版本目录或软链接切换，不直接覆盖唯一副本。
- 增加一键回滚脚本，至少支持：
  - 回滚后端镜像。
  - 回滚前端静态资源。
  - 查询本次迁移台账。
- 自动回滚是否启用可配置，默认先半自动。

**验收标准**
- 回滚演练能在目标时间内完成。
- 回滚后健康检查通过。
- 数据库迁移不可自动反向执行，必须明确提示。

**做完会发生什么**
- 发布故障从“人工 SSH 救火”变成“可演练流程”。
- 前端与后端版本可分别回退，减少耦合风险。

---

## 6. P1-A：冻结未完成同步模块

### Step 9：把同步模块的未完成项变成硬门禁

**要做什么**
- 以 `frontend/src/composables/sync/STATUS.md` 为准，逐项处理：
  - C05：探针三态化，未知必须阻断。
  - C07：回退前校验当前文件仍是同步产物。
  - C08：修正状态迁移非法路径。
  - C09：run/rollback 互斥。
  - C10：IndexedDB 缺键返回 `null` 而不是 `IDBRequest`。
  - C12：增加单文件、批次、journal 字节/数量上限。
  - C13：排除规则大小写、根级/嵌套、glob 语义对齐。
- 每项先写失败回归测试，再改实现。
- 在未修完前，禁止接入真实目录和 UI。

**验收标准**
- `STATUS.md` 中每个未完成项都有对应测试。
- 真实目录接入检查清单全部通过。
- 没有隐藏的“临时启用开关”。

**做完会发生什么**
- 同步模块不会在边界未稳时污染用户文件。
- 后续接线有明确、可验收的前置条件。

### Step 10：统一同步契约与后端游标语义

**要做什么**
- 固定 `afterChangeSetId` 与 `tailUpdateTime + tailChangeId` 两条进度线。
- 把 `Truncated` 标志真正传递到前端决策层。
- 对 `create / update / delete / rename` 和 `status` 做统一映射。
- 增加跨用户、跨项目、重复游标、截断内容、并发提交的集成测试。

**验收标准**
- 同一 change-set 内部分失败后重试不会漏文件。
- 截断内容不能被当作完整基线。
- 其他用户/项目的数据无法通过游标访问。

**做完会发生什么**
- 同步从“能返回数据”升级为“可安全应用到真实工作区”。

---
## 7. P1-B：拆分 Agent 运行核心

### Step 11：先固定 `AgentLoopEngine` 行为边界

**要做什么**
- 先补 characterization 测试，覆盖：
  - 单轮模型调用。
  - 多 tool call 顺序与终态。
  - 审批批准、拒绝、过期、重复提交。
  - SSE 断线、刷新、重复 cursor。
  - 恢复、取消、终态不可回 running。
  - 上下文超限与有限重试。
- 明确哪些逻辑属于：
  - 模型轮次
  - 工具调度
  - 审批/交互
  - 事件投影
  - 恢复
  - 子代理进度

**验收标准**
- 拆分前后这些测试结果一致。
- 测试能指出是哪个边界失败，而不是只报一个大类错误。

### Step 12：按职责拆出协作组件

**建议拆分**
| 新组件 | 职责 | 不应负责 |
|---|---|---|
| `AgentModelTurnCoordinator` | 模型轮次、provider 调用、usage | 工具执行、UI 投影 |
| `AgentToolDispatchCoordinator` | 工具批次、顺序、终态 | 模型协议、SSE 生命周期 |
| `AgentApprovalCoordinator` | 权限、审批、用户交互 | transcript 持久化 |
| `AgentEventProjector` | 持久化事件与 SSE 投影 | 业务状态决策 |
| `AgentRecoveryCoordinator` | 恢复、重试、取消、终态 | 新建第二套事实源 |
| `AgentLoopEngine` | turn 编排与依赖组合 | 所有具体细节 |

**验收标准**
- 每个组件有单一职责和独立测试。
- `AgentLoopEngine` 行数逐阶段下降，不是一次性重写。
- 所有状态迁移仍只经 `AgentRunLifecycleService`。
- 不新增第二套 transcript、审批等待或恢复状态。

**做完会发生什么**
- 单点改动影响面缩小。
- 新功能可以按边界增量接入。
- 测试更接近真实失败原因。

**风险与回滚**
- 风险：拆分过程中容易复制状态。
- 回滚：每个组件单独提交，保留旧调用路径直到新路径测试通过；不做大爆炸式重写。

### Step 13：收敛运行时配置和魔法数字

**要做什么**
- 把散落的超时、重试、预算、输出上限集中到现有 `@ConfigurationProperties`。
- 优先处理文档列出的已知点：
  - `LlmProviderFactory.buildConfig`
  - `OpenAiCompatibleProvider`
  - `CompactionAgent`
  - `MiniMaxChat` / `OllamaChat`
  - `AgentModelConfigService.DEFAULT_MAX_TOKENS`
  - `ProjectTerminalService` 与 `AgentApprovedCommandExecutor` 的重复上限
- 新增环境变量同步更新 README 和 `.env.example`。

**验收标准**
- 业务类不再新增 `static final` 魔法数字。
- 配置变更不需要改代码。
- 默认值与生产覆盖有测试。

**做完会发生什么**
- 不同模型/部署环境更容易调优。
- 配置漂移和重复常量减少。

---

## 8. P1-C：拆分前端工作区

### Step 14：先画清 `CloudWorkspace.vue` 的状态边界

**要做什么**
- 按域列出状态：
  - 会话与消息
  - 文件树与文件操作
  - diff / change-set
  - 终端
  - Web 预览
  - 模型配置
  - token / 计费
  - 布局与响应式
  - 弹窗与通知
- 标出哪些是 server state，哪些是 client UI state。

**验收标准**
- 每个状态只有一个 owner。
- 不在多个 composable 中重复保存同一事实。

### Step 15：按 feature container 拆分

**建议拆分**
| 模块 | 职责 |
|---|---|
| `useWorkspaceConversation` | 会话选择、消息投影、发送状态 |
| `useWorkspaceFiles` | 文件树、搜索、上传、冲突 |
| `useWorkspaceChanges` | diff、change-set、apply/reject/undo |
| `WorkspaceTerminalFeature` | 终端生命周期与尺寸 |
| `WorkspacePreviewFeature` | Web 预览与刷新 |
| `WorkspaceModelSettings` | 模型配置与选择 |
| `WorkspaceUsageFeature` | token、缓存、计费展示 |
| `CloudWorkspace.vue` | 布局、路由、feature 组合 |

**验收标准**
- 主视图只保留布局和组合逻辑。
- 每个 feature 有独立测试。
- 移动端、键盘导航、焦点行为不回归。
- 首屏与交互性能有基线数据。

**做完会发生什么**
- 前端改动不再需要理解 4,500 行文件。
- 终端、预览、模型配置可以独立迭代。
- 组件测试和 E2E 更容易定位问题。

### Step 16：建立性能预算与真实浏览器验收

**要做什么**
- 记录首屏 LCP、交互延迟、主包体积、CloudWorkspace chunk 体积。
- 对 Monaco、ECharts、Mermaid、xterm 做按需加载验证。
- 增加至少一条真实浏览器 smoke：
  - 登录
  - 打开项目
  - 发送消息
  - 收到流式响应
  - 刷新后恢复
  - 打开 diff 并 apply/reject
- 区分构建通过、单测通过和真实浏览器通过。

**验收标准**
- 性能预算在 CI 或固定脚本中可执行。
- smoke 失败能定位到具体步骤。
- 报告不把静态检查当作真实运行证据。

---
## 9. P2-A：接口与 DTO 收敛

### Step 17：Controller 变薄

**要做什么**
- Controller 只做 HTTP 适配：
  - 身份提取
  - 参数绑定
  - 调用 use-case
  - 返回 DTO/状态码
- 所有权检查下沉到 service/use-case。
- 统一异常映射，减少每个接口的 `try/catch` 和字符串错误。
- 对分页、排序、过滤统一边界校验。

**验收标准**
- Controller 不再包含业务流程。
- 其他用户/项目访问有负向测试。
- 错误响应结构一致，不泄露内部细节。

### Step 18：替换 `Map<String,Object>`

**要做什么**
- 优先替换以下高频边界：
  - `AgentStreamRequest`
  - 事件 payload
  - context preview
  - task resume
  - model config
  - sync feed 请求/响应
- 用明确 DTO 或 record 表达必填、可选和枚举。
- 对外 API 变更同步更新文档。

**验收标准**
- 新接口不再新增无 schema 的 `Map`。
- 旧接口有兼容期和删除条件。
- 序列化/反序列化有契约测试。

**做完会发生什么**
- 前后端契约更清晰。
- 运行时错误从“字段缺失”提前到编译/校验阶段。
- API 文档与实现更容易保持一致。

---

## 10. P2-B：文档与仓库卫生

### Step 19：把可共享工程文档纳入版本控制

**要做什么**
- 当前 `.gitignore` 忽略整个 `docs/`，但本地有 214 个文档。
- 把可共享文档迁到受控目录，例如：
  - `engineering-docs/`
  - `docs-public/`
  - `adr/`
- 保留 `docs/个人资料/` 等私有内容忽略。
- 至少纳入：
  - 运行时收敛计划
  - ADR / 架构决策
  - 验收标准
  - 发布与迁移手册
  - 迭代记录

**验收标准**
- 新克隆仓库能看到工程文档。
- `git ls-files engineering-docs/**` 不为空。
- 私有资料仍不会被提交。

**做完会发生什么**
- 换机器不丢知识。
- 新成员和 AI Agent 能读到同一套架构事实。
- 文档不会再只存在于某台电脑。

### Step 20：清理仓库卫生问题

**要做什么**
- 单独处理 `.git/objects/pack` 中只有 `.idx`、没有 `.pack` 的残留。
- 把本地 305 MB 级发布产物与仓库工作区分离。
- 检查 `.env.example` 与 README 的配置项一致性。
- 统一换行符策略，减少 `LF will be replaced by CRLF` 噪音。
- 明确未提交改动归属，避免功能提交混入清理。

**验收标准**
- `git fsck` 无异常。
- 仓库不再包含可再生成的大产物。
- 配置文档与实际字段一致。
- 提交历史按主题清晰分组。

**做完会发生什么**
- 克隆、备份、CI 缓存更稳定。
- 代码审查噪音减少。
- 不会把本地环境问题带进发布。

---

## 11. 依赖关系与建议批次

```text
批次 1（发布安全）
  Step 1 → Step 2 → Step 3
  Step 4 → Step 5 → Step 6
  Step 7 → Step 8

批次 2（冻结与运行核心）
  Step 9 → Step 10
  Step 11 → Step 12 → Step 13

批次 3（前端与接口）
  Step 14 → Step 15 → Step 16
  Step 17 → Step 18

批次 4（文档与卫生）
  Step 19 → Step 20
```

不建议并行：
- Step 4 与生产发布演练。
- Step 12 与 Step 18 同时大改同一运行时边界。
- Step 15 与 Step 16 同时重排前端状态与性能预算。

---

## 12. 全部完成后的项目状态

### 发布
- 每次发布前自动跑后端测试、前端测试、构建、迁移校验。
- 门禁本身有负向测试，不会假绿灯。
- 部署后自动健康检查，失败会明确阻断。
- 有可演练的镜像与前端回滚流程。

### 数据库
- 生产不再执行全量 `schema.sql` / `data.sql`。
- 结构变更只走幂等迁移和台账。
- 新库初始化与旧库升级路径清晰。
- 迁移失败能快速定位并安全重试。

### Agent 运行时
- `AgentLoopEngine` 只做 turn 编排。
- 模型、工具、审批、事件、恢复各有单一 owner。
- 关键状态机有真实回归覆盖。
- 配置集中，调优不需要改业务代码。

### 前端
- `CloudWorkspace.vue` 变成布局与 feature 组合入口。
- 会话、文件、diff、终端、预览、模型配置各自独立。
- 有真实浏览器 smoke 和性能预算。
- 移动端与可访问性有回归保护。

### 同步模块
- 未完成项有明确硬门禁。
- 游标、截断、冲突、回滚、并发都有测试。
- 在安全条件满足前不会接入真实用户目录。

### 工程协作
- 架构文档、ADR、验收标准进入版本控制。
- 新成员和 AI Agent 能从仓库恢复上下文。
- 仓库不含不可再生成的大产物。
- 提交按主题可审查、可回滚。

---

## 13. 最终验收清单

- [ ] CI 执行后端测试，失败即阻断。
- [ ] 本地 preflight 可复现，不依赖硬编码路径。
- [ ] 门禁有负向自检。
- [ ] production profile 禁用 `schema.sql` / `data.sql`。
- [ ] 迁移幂等性与台账有自动化测试。
- [ ] 部署后有应用级健康检查。
- [ ] 镜像与前端有可演练回滚。
- [ ] 同步模块未完成项全部有测试或明确阻断。
- [ ] `AgentLoopEngine` 已按职责拆分，主循环变薄。
- [ ] `CloudWorkspace.vue` 已按 feature 拆分。
- [ ] 关键 Controller 有授权负例测试。
- [ ] 新接口不再使用无 schema 的 `Map`。
- [ ] 工程文档进入版本控制。
- [ ] `git fsck` 无异常，仓库无大产物残留。

---

## 14. 本轮审计边界

已执行：
- `git status --short --branch`
- `git diff --check`
- `node scripts/check-file-lines.mjs`
- `node scripts/preflight-release.mjs`
- `git fsck --no-dangling`
- 只读检查安全配置、生产 profile、CI workflow、迁移器、同步模块、测试分布

未执行：
- 完整后端测试
- 真实 MySQL 集成
- 真实 JVM 启动
- 浏览器验收
- 生产部署
- 任何代码修改或清理

因此本文档是**优化路线图**，不是“已完成”报告。每完成一步，应在本文件勾选并补充实际命令、结果、剩余风险。