# OpenCode 风格 Tree Snapshot 迁移执行计划

> **执行约束：** 逐项实施。每一项在对应的 focused test 通过前不能勾选。保留当前工作区的无关修改；不修改用户项目自身 `.git`，不提交、不推送。

**目标：** 消除 write/edit/apply 每次两次全仓 Git commit 快照造成的阻塞，迁移到私有 Git 索引上的 tree snapshot 与后续 Agent step 边界聚合。

**设计依据：** `docs/superpowers/specs/2026-07-24-opencode-style-tree-snapshot-design.md`。

**技术栈：** Java 17、Spring Boot 3、MyBatis-Plus、Git CLI、JUnit 5、Mockito。

## 全局不变量

- 私有快照仓库位置仍为工作区 `.labex/git-snapshots`；不触碰项目 `.git`、暂存区或 hooks。
- 所有文件路径必须使用既有 `isTrackable` / 规范化规则；禁止 shell 拼接路径。
- 任何已落盘修改都必须有 before tree 或内容 fallback；失败状态必须可观测。
- 旧 commit ref 与新 tree ref 同时兼容，数据库迁移必须是加法式。
- 同一 workspace 的 Git 快照保持串行，避免 index 并发损坏。

---

### Task 0: 建立设计、基准与回归基线

**文件：**
- New: `docs/superpowers/specs/2026-07-24-opencode-style-tree-snapshot-design.md`
- New: `docs/superpowers/plans/2026-07-24-opencode-style-tree-snapshot-migration.md`
- Modify: 对应测试文件（只增加基准辅助时）

- [x] 记录当前写入链路、OpenCode 实现、性能根因和不采用方案。
- [ ] 为 `GitSnapshotService` 建立真实 Git 临时工作区基准：单文件 write 前后 capture 的命令数和耗时。
- [ ] 确认现有 `GitSnapshotServicePathTest`、rename、`DiffServiceCasTest` 覆盖的历史兼容行为。

### Task 1: Tree snapshot 基础设施（无 commit）

**文件：**
- Modify: `backend/src/main/java/com/labex/labexagent/diff/GitSnapshotService.java`
- Modify/add: `backend/src/test/java/com/labex/labexagent/diff/GitSnapshotService*Test.java`

- [ ] 将私有快照仓库配置移动到首次初始化；捕获热路径不得再次执行持久 `git config`。
- [ ] 使用私有索引的 `git write-tree` 返回 tree ref；移除 capture 热路径的 `HEAD`、`status`、`commit`。
- [ ] 保留全局 `capture(project,label)` 兼容入口；全局模式仍可收集命令工具未知范围的改动。
- [ ] 为 Git 子进程加入可靠的并发 stdout/stderr 读取和超时诊断，避免大量输出时 pipe 死锁。
- [ ] 测试 tree ref 可 diff、可按路径 restore，且旧 commit ref 兼容。

### Task 2: 变更路径增量跟踪

**文件：**
- Modify: `backend/src/main/java/com/labex/labexagent/diff/GitSnapshotService.java`
- Modify: `backend/src/main/java/com/labex/labexagent/diff/DiffService.java`
- Modify/add: `backend/src/test/java/com/labex/labexagent/diff/DiffService*Test.java`

- [ ] 新增 `capture(project, label, paths)`；使用定向 Git add 与 write-tree，仅跟踪规范化变更路径。
- [ ] `DiffService.stageAndApplyBatch` 在写前、写后传入已验证变更路径。
- [ ] 无关文件不得进入路径快照；新增、修改、删除、rename、批量 patch 均有回归测试。
- [ ] `changedFiles`、`diffForFile`、`restore` 兼容 tree ref；现有 `AgentFileChange` 字段无需破坏性迁移。
- [ ] 保持当前落库、CAS、工作区元数据刷新和索引失效顺序。

### Task 3: Step 级快照协调器与 pending 状态

**文件：**
- New: `backend/src/main/java/com/labex/labexagent/runtime/StepSnapshotCoordinator.java`（或沿用既有 runtime 协调器）
- Modify: `backend/src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java`
- Modify: `backend/src/main/java/com/labex/labexagent/diff/DiffService.java`
- Modify: `backend/src/main/java/com/labex/entity/AgentFileChange.java` / schema/migrator（仅在现有状态字段不足时）
- Modify/add: runtime、diff、service tests

- [ ] 纯读工具不触发快照；首个 mutation tool 懒创建 step before snapshot。
- [ ] mutation tool 写入后立即返回并创建可恢复的 pending 变更记录。
- [ ] 所有 step terminal path（成功、模型错误、工具错误、取消、超时）统一 finalize 一次 after tree。
- [ ] finalize 通过 CAS 更新 pending change；重复调用幂等，不覆盖已完成 ref。
- [ ] finalize 失败保存明确状态和 fallback，undo API 不得谎报成功。
- [ ] command 工具在可靠变更路径提取前继续使用全局快照。

### Task 4: 恢复、并发与对象复用

**文件：**
- Modify: `GitSnapshotService`、`DiffService`、Agent run recovery 关联代码（按审计结果）
- Modify/add: snapshot/recovery integration tests

- [ ] 进程重启后扫描或按需处理遗留 `PENDING_STEP`；区分可 finalize、可 fallback 与不可恢复。
- [ ] 验证同 workspace 快照串行、不同 workspace 不互相阻塞。
- [ ] 在真实 Git 项目上实现可选 object alternates/index seed；失败自动退回私有索引。
- [ ] 验证不修改 source Git config/index，不依赖用户的 Git hooks。

### Task 5: 性能验收与发布门槛

**文件：**
- Modify: 快照日志/测试（必要时）
- Modify: README 或故障排查文档（若运行日志、恢复语义对使用者可见）

- [ ] focused tests：`GitSnapshotService*Test`、`DiffService*Test`、相关 `AgentLoopEngine*Test`。
- [ ] 全量后端：`mvn -q test`。
- [ ] 打包：`mvn -q package -DskipTests`。
- [ ] 在 Windows 真实 Git 临时项目中对比旧/新路径：Git 进程数、before/after capture 时间、write 工具总时间。
- [ ] 进行一次真实 Agent 文件修改 smoke（不使用生产凭据），确认 SSE、diff 展示、undo、取消路径。

## 分批交付判定

- **第一批可发布：** Task 1 + Task 2 完成；write 的快照变为路径级 tree，保持同步 after capture，功能兼容风险最低。
- **第二批可发布：** Task 3 完成；工具返回从 after Git work 中解耦，具备 pending/finalize 恢复保证。
- **第三批可发布：** Task 4 完成；真实 Git 项目的首次索引与大仓库性能进一步优化。

任何批次都必须满足对应测试与性能证据；没有真实 Agent smoke 时，报告为“测试验证完成，运行时 smoke 未验证”，不能宣称线上问题已根除。