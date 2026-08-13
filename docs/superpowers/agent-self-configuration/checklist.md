# Agent 自我配置与真实环境执行验收清单

> **文档类型：** `checklist.md`
> **状态：** 待实施证据
> **版本：** 1.0
> **日期：** 2026-08-08
> **规格：** [spec.md](spec.md)
> **任务：** [tasks.md](tasks.md)

## 使用规则

- 本文只记录规格、阶段和发布门禁，不替代 [tasks.md](tasks.md) 的任务步骤与执行状态。
- 只有在命令实际运行、结果已记录并完成 spec 合规审查与代码质量/安全审查后，才能勾选条目。
- 每条证据必须记录命令、退出码、tests/failures/errors/skips、环境限制和证据路径；“编译成功”不能替代启动 JVM、浏览器或 live runtime 证据。
- `[ ]` 表示未通过或尚无证据，`[x]` 表示证据已审核通过。阻塞项保持未勾选，并在对应证据表写明 `BLOCKED` 和原因。
- Phase 5 可因 native Windows helper 尚不可用而保持未勾选；此时 `integrated-windows` 必须保持禁用，其他 phase 不得用 `unsafe-local` 代替。
- 真实 `wsl --shutdown` 只能在明确批准的独立验收环境执行，普通实现 Agent 不得运行。

## 文档门禁

- [x] `spec.md`、`tasks.md`、`checklist.md` 三个文件存在于 `docs/superpowers/agent-self-configuration/`。
- [x] 三个文件互相使用相对链接，且不存在指向旧设计/计划文件的有效引用。
- [x] `spec.md` 包含背景、范围、角色、FR/NFR、架构所有权、数据模型、API/事件、错误语义、迁移、验收和需求追踪。
- [x] `tasks.md` 包含完整实现 Agent 提示词、精确文件、TDD 步骤、命令、预期结果、禁止事项、依赖和返回结构。
- [x] `checklist.md` 独立承载阶段退出、跨层一致性、安全、live acceptance 和最终发布门禁。
- [x] 文档结构无未解释占位符；实施门禁的未勾选项均有明确含义，且没有互相矛盾的权威来源。
- [ ] `.gitignore` 使该 feature 文档目录可追踪，同时个人/private docs 继续被忽略。
- [ ] `.labex-agent/project/**` 只有规格允许的非敏感文件可追踪；runtime、lease、cache、credential 和 generated state 继续被忽略。

## 基线证据

| Evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| HEAD/branch | `git rev-parse HEAD` and `git branch --show-current` | SHA 与分支已记录 | `aae859717afe67b87a22e7e88ffdffe089b0be94` / `codex/agent-tool-reliability` | `[x]` |
| Working tree | `git status --short` | 用户改动已分类，无清理/覆盖 | Dirty baseline captured; `.gitignore` and existing backend/frontend/acceptance changes remain untouched; full per-file classification is still pending | `[ ]` |
| Focused backend baseline | `mvn -f backend/pom.xml '-Dtest=AgentRunStateMachineTest,AgentRunInteractionDispatchClaimTest,AgentRunResumeSchedulerTest,AgentRunExecutionLeaseServiceTest,AgentRunRecoveryServiceTest,AgentRunContinuationRequestFactoryTest' test` | 真实计数已记录 | `BUILD SUCCESS`; tests 200, failures 0, errors 0, skipped 0 | `[x]` |
| Frontend baseline | `npm --prefix frontend test` | 真实计数已记录 | `tests 231`, pass 231, fail 0, skipped 0 | `[x]` |
| Frontend build | `npm --prefix frontend run build` | Production build 通过或阻塞已分类 | `BUILD SUCCESS`; Vite build and chunk budget passed; dependency PURE annotation warnings only | `[x]` |

## Requirement Traceability Gate

| Requirement | Primary tasks | Verification status |
|---|---|---|
| FR-001 Project config package | 0.0, 1.1, 1.3 | `[ ]` |
| FR-002 Strict validation/digest/external review | 1.1, 1.3 | `[ ]` |
| FR-003 Revision/proposal/audit/evidence | 1.2, 1.3, 2.1, 2.2 | `[ ]` |
| FR-004 Immutable task-epoch snapshot | 0.7, 1.2, 1.4 | `[ ]` |
| FR-005 Owner-only proposal apply | 2.1, 2.2, 2.3 | `[ ]` |
| FR-006 Write-only secret and scoped lease | 2.4, 3.2, 4.2 | `[ ]` |
| FR-007 Single capability policy | 3.1 | `[ ]` |
| FR-008 Epoch-scoped Agent/resource/tool catalog | 3.2, 3.3, 3.4 | `[ ]` |
| FR-009 Explicit Worker profiles | 4.1, 4.2, 5.2, 5.3 | `[ ]` |
| FR-010 Durable environment operations | 4.3, 4.4 | `[ ]` |
| FR-011 Guarded WSL host maintenance | 4.4, 7.2 | `[ ]` |
| FR-012 Fenced durable recovery | 0.1-0.8 | `[ ]` |
| FR-013 Frontend configuration and replay | 6.1-6.3 | `[ ]` |
| FR-014 Feature-gated compatibility exit | 7.1, 7.2 | `[ ]` |
| NFR-001 Ownership/authorization | 0.2, 0.3, 1.3, 2.2, 2.4, 3.1-3.3 | `[ ]` |
| NFR-002 Idempotency/CAS/transaction | 0.3-0.6, 0.8, 1.2-1.4, 2.1-2.3, 4.2-4.4 | `[ ]` |
| NFR-003 Secret non-disclosure | 0.5-0.6, 1.1-1.4, 2.1-2.4, 3.2, 4.2, 5.1-5.2 | `[ ]` |
| NFR-004 Durable replay/recovery | 0.3-0.6, 0.8, 1.4, 2.3, 3.2-3.3, 4.3-4.4, 6.1-6.3 | `[ ]` |
| NFR-005 Fail-closed behavior | 0.2-0.8, 1.1-1.4, 3.1-5.2 | `[ ]` |
| NFR-006 Additive migration | 0.3, 0.7, 1.2, 2.1, 2.4, 4.3, 7.1 | `[ ]` |
| NFR-007 Accessible responsive UI | 6.1-6.3 | `[ ]` |
| NFR-008 Audit/observability | 1.3, 2.1-2.4, 4.3-4.4, 7.1 | `[ ]` |
| NFR-009 Dirty-worktree preservation | 0.0 and every implementation task | `[ ]` |
| NFR-010 No second fact source | 0.4, 1.4, 2.3, 3.1-3.3, 6.1-6.3 | `[ ]` |

## Phase 0 Gate: Runtime Safety

- [ ] Task 0.0 已记录共享脏工作区基线并修复文档/项目配置 Git 可见边界。
- [ ] 公共 `/agent/stream` 拒绝 `resumeTaskId` 和 `resumeInteractionId`，且未调用 command service 或 Agent loop。
- [ ] scheduler/continuation factory 仍可构造 server-owned internal resume request。
- [ ] interaction resume 在单事务中校验 owner、project、task、latest resolved interaction、waiting state、TTL、unconsumed claim 和 lease。
- [ ] 并发 interaction dispatch 恰好一个 claimant 成功，重复 decision/dispatch 返回第一次 durable result。
- [ ] executor-originated transcript、Message、Part、event/outbox、artifact、plan 和 completion 写入全部要求 active `ExecutionFence`。
- [ ] old owner、old epoch、expired lease 对所有 durable fact family 均 fail closed 且无部分写入。
- [ ] 新 task 持久化精确 model config；恢复不读取当前默认、不做静默 fallback。
- [ ] `plan_exit` 持久化 mode，并在下一 Provider call 重建 prompt、tool schema、policy 和 cache key。
- [ ] 状态机允许 `RECOVERING -> PREPARING` 和受控 `PREPARING -> WAITING_ENVIRONMENT`，终态不可回到 running。
- [ ] bounded periodic lease reconciler 可恢复 stranded non-terminal task，不接管 active lease 或 unresolved interaction。
- [ ] Phase 0 focused tests 与完整 backend suite 无新增失败，平台/环境 skip 已单独记录。

| Phase 0 evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| Security characterization | `mvn -f backend/pom.xml '-Dtest=StudentAgentControllerStreamSecurityTest,AgentRunExecutionFenceTest,AgentRunModelSelectionServiceTest,AgentRunLeaseReconcilerTest,AgentRunInteractionDispatchClaimTest,AgentRunStateMachineTest' test` | `CHARACTERIZATION_RED` records only the intended missing-secure-behavior failures | Not recorded | `[ ]` |
| Runtime regression | `mvn -f backend/pom.xml '-Dtest=AgentRunContinuationRequestFactoryTest,AgentRunResumeSchedulerTest,AgentRunLifecycleServiceTest,AgentRunTranscriptServiceTest,AgentRunPartServiceTest,AgentRunRecoveryServiceTest,AgentRunTakeoverSchedulerTest,AgentRunLeaseReconcilerTest,AgentToolTurnExecutorTest' test` | No regression after implementation tasks | Not recorded | `[ ]` |
| Backend full | `mvn -f backend/pom.xml test` | No new failure; skips classified | Not recorded | `[ ]` |

## Phase 1 Gate: Config Source, Revision and Snapshot

- [ ] Reader 只接受 owned workspace 内 `.labex-agent/project/agent.json` 及 manifest 声明的 child files。
- [ ] traversal、absolute path、symlink、超限文件、未知字段、secret-looking key、无效引用和不支持 profile 被拒绝。
- [ ] canonicalization 对格式差异产生同一 digest，对语义差异产生不同 digest。
- [ ] revision 与 task-epoch snapshot schema/additive migration 可重复执行且不破坏数据。
- [ ] config writer 使用 project-root lock、临时文件、原子替换、写后 digest 校验和失败清理。
- [ ] external semantic edit 先记录 `external_change_pending` 并阻塞执行，随后由 Phase 2 proposal authority 创建 owner-only `external_change` proposal；不得静默更新 effective revision。
- [ ] 所有配置 API 先校验 authenticated owner/project；foreign project 与 not-found 不可区分。
- [ ] stale revision 使用真实 HTTP `409`。
- [ ] 新 task 在进入 `queued` 前创建 immutable snapshot；恢复新 epoch 不修改旧 snapshot。
- [ ] 旧 active task 缺失精确资源时 fail closed，不读取 mutable default。

| Phase 1 evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| Config parser | `mvn -f backend/pom.xml '-Dtest=AgentProjectConfigReaderTest,AgentProjectConfigValidatorTest,AgentProjectConfigCanonicalizerTest' test` | Path/schema/digest cases pass | Not recorded | `[ ]` |
| Schema/migration | `mvn -f backend/pom.xml '-Dtest=AgentProjectConfigSchemaTest,AgentProjectConfigRevisionMapperDatabaseTest,AgentRunConfigSnapshotMapperDatabaseTest,AgentProjectConfigExternalChangeMapperDatabaseTest,AgentRunSchemaTest' test` | Additive/idempotent migration passes | Not recorded | `[ ]` |
| Revision/snapshot | `mvn -f backend/pom.xml '-Dtest=AgentProjectConfigRevisionServiceTest,AgentProjectConfigControllerTest,AgentEffectiveProjectConfigServiceTest,AgentRunConfigSnapshotServiceTest' test` | Ownership/revision/snapshot cases pass | Not recorded | `[ ]` |

## Phase 2 Gate: Proposal, Approval, Audit and Secrets

- [ ] proposal 保存 base revision、candidate digest、patch/tree reference、origin task/epoch/toolCall、expiry 和幂等字段，不保存秘密。
- [ ] audit append-only，记录 actor/reason/status/digests/paths/task/epoch，不能 update/delete 历史。
- [ ] proposal approve/reject 仅由当前 owner 对 pending、未过期、base revision 匹配的 proposal 执行。
- [ ] apply 使用 `applying`、staging、atomic publish、revision/CAS、audit、event/outbox 和 durable reconciliation；任一崩溃点都不能产生 false applied revision。
- [ ] 重复 proposal/decision key 返回第一次 durable result，stale/conflict 返回安全 4xx/409。
- [ ] `config_proposal` interaction 仅是 proposal authority 的等待投影。
- [ ] Tool Part 在等待前持久化并保持原始 `toolCallId`；同 turn 剩余 Part 明确 `skipped` 或 `interrupted`。
- [ ] `propose_project_config` 是 Agent 唯一配置变更入口，不能 apply、rotate secret 或执行 host maintenance。
- [ ] `secret-input` 绑定 owner/project/proposal/input ID/TTL/幂等键，并立即进入 SecretStore。
- [ ] `ScopedSecretLease` 绑定 task/epoch/worker run/allowed names/expiry 并保证一次性 cleanup。
- [ ] secret sentinel 不出现在任何 config、proposal、snapshot、interaction、transcript、Part、event/outbox、SSE、命令行、Worker output、日志和错误中。

| Phase 2 evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| Proposal persistence | `mvn -f backend/pom.xml '-Dtest=AgentProjectConfigProposalMapperDatabaseTest,AgentRunSchemaTest' test` | Idempotency/audit schema passes | Not recorded | `[ ]` |
| Proposal apply | `mvn -f backend/pom.xml '-Dtest=AgentProjectConfigProposalServiceTest,AgentProjectConfigControllerTest,AgentProjectConfigChangeEvidenceServiceTest,AgentProjectConfigApplyRecoveryServiceTest' test` | Owner-only CAS and staged-apply recovery pass | Not recorded | `[ ]` |
| Durable projection | `mvn -f backend/pom.xml '-Dtest=AgentRunConfigProposalInteractionTest,ProposeProjectConfigToolTest,AgentRunEventReplayServiceTest' test` | Replay/toolCall correlation passes | Not recorded | `[ ]` |
| Secrets | `mvn -f backend/pom.xml '-Dtest=ProjectSecretBindingServiceTest,ScopedSecretLeaseServiceTest,LocalEnvelopeSecretStoreTest,AgentModelConfigSecretTest,AgentMcpServerSecretTest' test` | Sentinel absent and leases bounded | Not recorded | `[ ]` |

## Phase 3 Gate: Capability and Extensions

- [ ] `AgentCapabilityPolicy` 的输入只来自 immutable snapshot、platform ceiling 和 trust state。
- [ ] schema generation 与 execution 使用同一 policy decision。
- [ ] secret、metadata、cross-user data 和 protected config 是 platform hard deny。
- [ ] 旧 mode allowlist、selected-name execution check 和 permission append-order 在 shadow compare 通过后退出。
- [ ] model、MCP、Skill 和 named Agent 从 epoch snapshot 读取，不受运行中 catalog/default 变化影响。
- [ ] owner-owned resource 未被项目选择时不可用；删除/禁用影响新 epoch，不静默改变当前 epoch。
- [ ] `ToolRegistry` 只拥有 built-in directory，epoch catalog 隔离 project script 和 MCP dynamic tools。
- [ ] 两个项目的同名动态工具和两个 epoch 的 Agent 定义不会互相泄漏。
- [ ] 项目脚本只从 declared path 加载，通过 Worker 和 capability policy 执行。
- [ ] 项目脚本 stdin/stdout JSON、stderr、timeout、output limit、network 和 cancellation 边界均有测试。

| Phase 3 evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| Capability parity | `mvn -f backend/pom.xml '-Dtest=AgentCapabilityPolicyTest,ToolRegistryModeTest,ToolSelectionPolicyTest,AgentToolTurnExecutorTest,PermissionServicePersistenceTest' test` | Schema/execution parity passes | Not recorded | `[ ]` |
| Resource/catalog isolation | `mvn -f backend/pom.xml '-Dtest=AgentEffectiveResourceResolverTest,AgentDefinitionResolverTest,AgentEpochToolCatalogTest,McpToolAdapterWorkerTest' test` | Snapshot and project isolation pass | Not recorded | `[ ]` |
| Project scripts | `mvn -f backend/pom.xml '-Dtest=ProjectScriptToolLoaderTest,ProjectScriptToolExecutorTest,ToolArgumentSchemaValidatorTest,CommandToolWorkerTest' test` | Protocol/security cases pass | Not recorded | `[ ]` |

## Phase 4 Gate: Integrated Worker and Environment Operations

- [ ] `WorkerRunSpec` 明确 profile、runtime target、distribution、network class、writable roots、safe env names、secret lease 和 verification policy。
- [ ] Spring 只暴露一个 `@Primary SandboxWorkerRouter`，不存在冲突的直接 Worker bean authority。
- [ ] router 只按 immutable spec 选择 delegate；缺失/不健康 integrated delegate 返回结构化基础设施失败。
- [ ] terminal、LSP、MCP 在无 task 时默认 strict，不能借用 privileged Agent profile。
- [ ] `unsafe-local` 不可由项目选择，也不是失败 fallback；integrated profile 不可用时返回结构化基础设施失败，只有显式选择 strict 的新 spec 才能运行 strict。
- [ ] integrated WSL 保留 workspace validation、clear environment、secret lease、process-tree supervision 和 network policy。
- [ ] environment operation 持久化 type/scope/attempt/deadline/digests/failure/idempotency，并使用 bounded state machine。
- [ ] 自动修复默认只允许项目/用户级 dependency；system package/PATH/certificate/service/driver 进入高风险 proposal。
- [ ] retryable environment failure 进入 `waiting_environment`，恢复创建新 epoch/snapshot；non-retryable failure 停止。
- [ ] `.wslconfig` 使用 typed allowlist schema，拒绝未知 key 和 raw-text replacement。
- [ ] global maintenance lease 阻止新 WSL task/terminal/LSP/MCP，drain 超时取消 shutdown。
- [ ] unit tests 仅使用 fake host adapter，不执行真实 `wsl --shutdown`。

| Phase 4 evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| Worker profiles | `mvn -f backend/pom.xml '-Dtest=AgentRuntimeProfileTest,SandboxWorkerRouterTest,SandboxWorkerContractTest' test` | Profile/router contracts pass | Not recorded | `[ ]` |
| Worker entry points | `mvn -f backend/pom.xml '-Dtest=IntegratedWslWorkerTest,SandboxWorkerContractTest,WslSandboxWorkerTest,DockerSandboxWorkerSmokeTest,CommandToolWorkerTest,ProjectTerminalServiceWorkerTest,LspSessionManagerWorkerTest,McpToolAdapterWorkerTest' test` | Profile propagation and strict boundaries pass | Not recorded | `[ ]` |
| Environment operations | `mvn -f backend/pom.xml '-Dtest=AgentEnvironmentOperationServiceTest,AgentEnvironmentProbeServiceTest,AgentEnvironmentOperationControllerTest,EnvironmentBlockerClassifierTest,CommandFailureGuardTest' test` | Durable bounded recovery and API ownership pass | Not recorded | `[ ]` |
| WSL maintenance | `mvn -f backend/pom.xml '-Dtest=WslConfigValidatorTest,GlobalMaintenanceLeaseServiceTest,EnvironmentDrainCoordinatorTest,AgentEnvironmentOperationServiceTest,SandboxWorkerRouterTest,LspSessionManagerWorkerTest,McpClientWorkerTest' test` | Drain and fake adapter cases pass | Not recorded | `[ ]` |

## Phase 5 Gate: Windows Native Sandbox

- [ ] helper contract 使用 versioned stdin/stdout JSON，覆盖 start/execute/terminal/terminate/health/cleanup。
- [ ] helper 使用低权限进程边界、Job Object/process-tree cleanup、workspace ACL 和可验证 network policy。
- [ ] Java client 校验 helper version/protocol/timeout/cleanup；明确请求 integrated profile 时返回结构化不可用失败，只有显式选择 strict 的新 spec 才能使用 strict Worker。
- [ ] PowerShell wrapper 未被当作安全 sandbox 实现。
- [ ] fake helper tests 覆盖 workspace write、outside write、child escape、network、secret cleanup、timeout 和 crash。
- [ ] live acceptance 记录 binary hash/version/PID/parent/startup time 和 pinned toolchain。
- [ ] live negative tests 覆盖 outside workspace、metadata、LAN、child persistence 和 secret extraction。
- [ ] helper 停止后 process tree、临时 secret lease 和 runtime files 已清理。
- [ ] 所有 live 证据通过前 `integrated-windows` 保持 disabled。

| Phase 5 evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| Native helper build | `cmake -S sandbox/windows-helper -B sandbox/windows-helper/build -G "Visual Studio 17 2022"; cmake --build sandbox/windows-helper/build --config Release; ctest --test-dir sandbox/windows-helper/build -C Release --output-on-failure` | Pinned helper build and native contract tests pass, or helper gate is BLOCKED | Not recorded | `[ ]` |
| Java/helper contract | `mvn -f backend/pom.xml '-Dtest=WindowsSandboxHelperClientTest,AgentRuntimeProfileTest,SandboxWorkerContractTest' test` | Fake helper contract and explicit unavailable failure pass | Not recorded | `[ ]` |
| Live helper | `powershell -ExecutionPolicy Bypass -File scripts/acceptance/windows-sandbox-runtime.ps1` | Security report attached | Not recorded | `[ ]` |

## Phase 6 Gate: Frontend Configuration Center

- [ ] API 调用全部集中在 `frontend/src/api/index.js` 的 `projectAgentConfigApi`，组件无裸 Axios。
- [ ] pure projection 以 project/revision/proposal/operation ID 为 key，不以 SSE open/close 推断状态。
- [ ] project switch 清理旧项目 projection，重复响应/cursor 幂等。
- [ ] `AgentConfigPanel` 仅编排，JSON 解析/API/任务 reducer 不进入组件。
- [ ] 配置编辑器显示结构化 validation 和完整 redacted proposal diff。
- [ ] trust/runtime 只能表达用户决策，不能让项目自提升 capability。
- [ ] secret input write-only，成功后清空明文并只显示 alias/configured。
- [ ] environment UI 覆盖 waiting approval、draining、executing、health check、failed、recovered。
- [ ] 配置 proposal 通过 taskId/epoch/interactionId/toolCallId/proposalId 关联，不按最后一个 tool card 猜测。
- [ ] HTTP history、SSE replay、refresh 和 duplicate cursor 进入同一 reducer。
- [ ] 表单/交互有 label 和键盘支持，桌面/移动端无文本溢出或重叠。
- [ ] `CloudWorkspace.vue` 保持编排边界，无新增大块业务逻辑。

| Phase 6 evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| Projection focused | `node --test frontend/src/composables/agentProjectConfigProjection.test.mjs` | Projection cases pass | Not recorded | `[ ]` |
| Frontend full | `npm --prefix frontend test` | All frontend tests pass | Not recorded | `[ ]` |
| Frontend build | `npm --prefix frontend run build` | Production build passes | Not recorded | `[ ]` |
| Browser smoke | `powershell -ExecutionPolicy Bypass -File scripts/acceptance/browser-runtime.ps1` | Existing refresh/replay/UI smoke evidence attached; config live scenario is gated by Phase 7 | Not recorded | `[ ]` |

## Phase 7 Gate: Compatibility Exit and End-to-End

- [ ] project config read、proposal apply、integrated Worker 和 host maintenance 使用独立 feature flags，生产默认 fail closed。
- [ ] unsnapshotted task、legacy model fallback、plaintext runtime env、in-memory approval 和 old resource read 有 durable readiness counters。
- [ ] 兼容路径记录 read/write direction、observation window、删除条件、目标版本和验证命令。
- [ ] README 记录 config layout、secret alias、profile、host maintenance 影响和 live verification；不把 `unsafe-local` 写成安全模式。
- [ ] isolated test user/project 已创建，记录 project/task/conversation/session/epoch/cursor/JVM/browser 标识。
- [ ] direct-resume、single interaction dispatch、duplicate decision/cursor 和 stale epoch negative cases通过。
- [ ] Maven/npm/Vite/Spring Boot 使用真实 integrated toolchain，并验证公网、localhost、workspace、LAN/metadata 边界。
- [ ] WSL maintenance live test 仅在显式批准环境执行，failed drain 不 shutdown，成功后 health check 和新 epoch 恢复。
- [ ] source tests、DB migration、started JVM、browser 和 live runtime 证据分别记录。
- [ ] live failure 作为失败 gate 报告，没有通过 exclude、skip 或修改断言隐藏。

| Phase 7 evidence | Command | Required result | Actual result | Status |
|---|---|---|---|---|
| Backend full | `mvn -f backend/pom.xml test` | All source tests pass; skips classified | Not recorded | `[ ]` |
| Frontend full | `npm --prefix frontend test` | All frontend tests pass | Not recorded | `[ ]` |
| Frontend build | `npm --prefix frontend run build` | Production build passes | Not recorded | `[ ]` |
| Runtime acceptance | `powershell -ExecutionPolicy Bypass -File scripts/acceptance/project-agent-config-runtime.ps1` | Durable/runtime evidence attached | Not recorded | `[ ]` |
| Browser acceptance | `powershell -ExecutionPolicy Bypass -File scripts/acceptance/browser-runtime.ps1 -BrowserScript project-agent-config-browser.mjs` | Wrapper-started JVM/H2/Vite/browser evidence attached | Not recorded | `[ ]` |

## Cross-Layer Consistency

- [ ] 每个新增表/列同时更新 `schema.sql`、`AdditiveSchemaMigrator`、entity、mapper 和数据库测试。
- [ ] 每个新增 task state/transition 只通过 `AgentRunLifecycleService`，并记录 actor/reason/previous/next/epoch/time。
- [ ] 每个新增 event 同时更新 durable event/outbox、DTO/payload、replay、前端 history/live reducer、测试和文档。
- [ ] 每个新增 interaction type 同时更新 authority、projection、expiry、claim、resume、refresh 和 duplicate tests。
- [ ] 每个新增 Tool Part 保持原始 `toolCallId`、name、arguments/result ownership 和明确终态。
- [ ] 每个新增 API 有 request validation、owner/project authorization、正确 HTTP status、redacted error 和 negative tests。
- [ ] schema generation 和 tool execution 使用同一 capability decision。
- [ ] task-epoch snapshot 能独立重建 Provider request、prompt、tool schema、resource selection 和 Worker policy。
- [ ] SSE/EventSource、Pinia/composable 和日志均不是运行状态或审批事实源。
- [ ] compatibility projection 可由新权威事实重建，旧路径达到删除条件后删除。

## Security and Privacy

- [ ] authentication 与 authorization 在每个 protected endpoint 分别执行。
- [ ] another user、another project、another task、another proposal 和 another resource negative cases 全部 fail closed。
- [ ] writable fields 使用 allowlist，客户端 actor/owner/project/epoch/role 不被信任。
- [ ] 配置路径拒绝 traversal、absolute path、symlink、protected path 和 workspace escape。
- [ ] user-provided URL 经过 outbound policy，metadata/internal ranges 永久拒绝或按规格审批。
- [ ] state-changing API 使用幂等键、CAS 和事务，重复请求没有重复副作用。
- [ ] secret sentinel 扫描覆盖数据库序列化对象、HTTP response、SSE、日志、命令行和 Worker output。
- [ ] 所有外部进程有 timeout、cancellation、process-tree cleanup 和 bounded output。
- [ ] full-access 每 task 有显式高风险用户决策，项目文件/Agent proposal 不能单独开启。
- [ ] real WSL shutdown 和 native Windows helper 有独立 live approval/evidence gate。

## Acceptance Criteria

- [ ] **AC-001:** 项目按用户选择运行于 strict、integrated-wsl 或已验证 integrated-windows。
- [ ] **AC-002:** Maven、npm、Vite、Spring Boot 使用真实工具链并访问允许的公网/localhost。
- [ ] **AC-003:** workspace 外写入、秘密、metadata、LAN 和未批准系统操作被拒绝或进入正确 interaction。
- [ ] **AC-004:** 配置变更展示 redacted diff、base revision、digest、actor、applied revision、audit 和验证证据。
- [ ] **AC-005:** proposal、secret input、dispatch、tool call 和 completion 重复提交无重复副作用。
- [ ] **AC-006:** SSE 断开、刷新、重启、lease 过期和跨 JVM 接管均可恢复。
- [ ] **AC-007:** 多 tool call、toolCallId、Part、epoch 和 proposal 状态重放一致。
- [ ] **AC-008:** WSL shutdown 仅在 approval、global drain、maintenance lease 和 health recovery 成功后执行。
- [ ] **AC-009:** 秘密不出现在任何禁止的持久化、传输、命令或日志表面。
- [ ] **AC-010:** 源码、DB migration、started JVM、browser 和 live runtime 证据分别记录并通过。

## Final Review

- [ ] 每个 task 均有先失败后通过的 TDD 证据；纯文档/配置任务有等价结构化验证。
- [ ] 每个 implementation Agent return packet 已完成 spec 合规审查和代码质量/安全审查。
- [ ] 无新增第二套 Provider transcript、task status、approval waiter、global dynamic tool registry 或 command executor。
- [ ] 无永久 fallback、无未写退出条件的兼容代码、无静默 `unsafe-local` 降级。
- [ ] 用户原有脏工作区改动未被覆盖、还原、清理、暂存或混入无关 diff。
- [ ] `git diff --check` 对所有交付文件无 whitespace error。
- [ ] 文档、测试、实现和 live evidence 的行为描述一致。
- [ ] 所有未勾选项都有明确 blocker，不以“基本完成”代替通过。

## Release Decision

| Decision | Required evidence | Result |
|---|---|---|
| Enable project config read | Phase 0 + Phase 1 | Not approved |
| Enable proposal apply | Phase 0 + Phase 1 + Phase 2 | Not approved |
| Enable project capability overrides | Phase 0-Phase 3 | Not approved |
| Enable integrated WSL | Phase 0-Phase 4 plus Phase 7 live WSL evidence | Not approved |
| Enable integrated Windows | Phase 0-Phase 5 plus native helper live evidence | Not approved |
| Enable frontend default entry | Backend gates plus Phase 6 | Not approved |
| Enable host maintenance | Phase 0-Phase 4 plus Phase 7 explicit WSL live acceptance | Not approved |
| Remove compatibility reads | Phase 7 readiness counters and observation window | Not approved |

Final release remains **not approved** until every required gate for the selected feature flags is checked and the corresponding evidence is attached.
