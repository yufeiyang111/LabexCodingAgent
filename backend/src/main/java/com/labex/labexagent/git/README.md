# 项目版本库（Git）+ 源码管理面板设计

> **为什么这份说明在代码目录里**：`docs/` 整个目录被 `.gitignore` 忽略（第 75 行），
> 换机器 / 新克隆 / CI 都读不到。设计契约必须跟代码一起走。
> 同源副本在 `docs/superpowers/specs/2026-11-06-project-git-repository-design.md`（本机可见）。

**日期**：2026-11-06
**状态**：设计待评审
**相关**：`frontend/src/composables/sync/STATUS.md`、`backend/src/main/java/com/labex/labexagent/diff/GitSnapshotService.java`

## 1. 两套系统，一条边界

Agent 的改动和用户的 git 提交是**两个独立系统**，唯一的接触点是工作区文件。

```
┌─────────────────────────────────────┐   ┌──────────────────────────────────┐
│ Agent 改动（不进 git）                │   │ 用户提交（真 git）                 │
│                                     │   │                                  │
│  change-set + diff + undo           │   │  源码管理面板 → 勾选 → 提交        │
│  现有 ChangesPanel，原样保留          │   │  GitHistoryPanel：历史 / diff /   │
│                                     │   │  回滚到某次提交                    │
│  生命周期：跟对话/任务走，结束就归档    │   │  生命周期：永久历史，可 clone       │
└──────────────────┬──────────────────┘   └───────────────┬──────────────────┘
                   │                                      │
                   └──────────► 工作区文件 ◄───────────────┘
                        （唯一的接触点）
```

**Agent 的改动直接写进工作区**，用户可以看 diff、可以选择回退（撤销改动）。
git 不知道哪些是 Agent 改的，也不该知道——**git 只记录"当前工作区相对上次提交的差异"**。

这不是妥协，是 git 的本意。VSCode 也不会告诉你"这行是谁改的"，它只显示工作区状态。

### 1.1 为什么 Agent 的改动不进 git

参照 DeepSeek Harness 的模型（`dsh-client-ui-tool/.../diff-card-model.d.ts`）：

> the `card:'diff'` render intent the write/edit tools declare arrives on the snapshot
> as `callView`/`resultView` ... The result side is authoritative once the call settles:
> the write/edit tools return the applied contextual hunks there (an edit's real
> before/after, a create's whole-file diff).

Agent 改动的 diff 属于**「这次工具调用」**，跟着对话走，不跟着版本库走。
两者生命周期完全不同：对话结束，diff 就该归档；git 历史则要永久保留。

把 Agent 改动塞进 git 会带来三个实际问题：
1. **提交历史被 Agent 的碎改动淹没**，用户找不到自己提交的东西；
2. **用户无法控制提交内容**——Agent 改完就进历史，等于自动提交；
3. **回退语义冲突**：Agent 的 undo 是"撤销这次工具调用"，git 的 revert 是"新增一条反向提交"，混在一起会互相打架。

### 1.2 Agent 侧现有能力（不新建）

| 能力 | 端点 | 状态 |
|---|---|---|
| 看 diff | `GET /agent/changes` + `ChangesPanel` | ✅ 已有 |
| 应用 | `POST /diff/{changeId}/apply` | ✅ 已有 |
| 拒绝 | `POST /diff/{changeId}/reject` | ✅ 已有 |
| 撤销 | `POST /diff/{changeId}/undo` | ✅ 已有 |

**本轮不改动 Agent 侧任何代码。** `ChangesPanel` 原样保留。

## 2. 现状：云端已有一个 git 仓库，但不能直接用

已实测确认：

- `GitSnapshotService` 在 `.labex/git-snapshots` 维护 bare 仓库。项目 2 有 31 条提交、
  745 个松散对象、1 个 pack（110KB）。
- 四个硬伤：

| 问题 | 实测 |
|---|---|
| **91.5% 是垃圾** | 23153 个文件里 21035 个 `node_modules`、1897 个 `npm-cache`、1821 个 `dist`、141 个 `.labexagent`。根因是 2026-07-24 一条 `before shell task 52` 提交用了全局快照（`git add -A` 无排除）。 |
| **内容不完整** | 磁盘 76 个源码文件，仓库只有 56 个，**缺 20 个**（含 `CommentController`、`CommentService`、`PostView`、`SearchPage.jsx`）。 |
| **提交时机不可控** | `GitSnapshotService` **从不 commit**（只 `write-tree`）。那 31 条提交是 **Agent 在对话里自己跑 `git commit`** 产生的。最后一次停在 7-24，而 change-set 记到 8-14。 |
| **没有对外接口** | 无 API，前端无 git 客户端。 |

**用户手改的代码完全不进版本库**：网页编辑器保存走 `PUT /files`，只写磁盘、不碰 git。

## 3. 已确认的决策

| 决策点 | 选择 |
|---|---|
| Agent 改动 | **直接写工作区**，可看 diff、可回退；**不进 git** |
| 历史处理 | **重建干净历史**，丢弃被污染的 31 条 |
| 提交粒度 | **按文件勾选** |
| 仓库位置 | **复用** `.labex/git-snapshots` |
| clone 鉴权 | URL 内嵌**短期 token** |

## 4. 架构

```
┌─────────────────────────────────────────────────────────┐
│ 网页（CloudWorkspace → 审查页）                            │
│   ChangesPanel（已有，不改）  Agent 改动的 diff / undo      │
│   SourceControlPanel（新增） 未提交改动 / 勾选 / 提交        │
│   GitHistoryPanel（新增）    提交列表 / diff / 回滚          │
└────────────────┬────────────────────────────────────────┘
                 │ REST（JWT）
┌────────────────▼────────────────────────────────────────┐
│ Spring Boot                                              │
│   ProjectGitController                                   │
│     /git/status    未提交改动                              │
│     /git/stage     暂存勾选文件                            │
│     /git/commit    提交                                   │
│     /git/commits   历史    /git/commits/{sha}  详情        │
│     /git/diff      提交级 diff                            │
│     /git/revert    回滚到某次提交                          │
│     /git/clone-url 带 token 的 clone 地址                  │
│   ProjectGitService          上面这些的实现                 │
│   GitSnapshotService（已有）  快照，只用临时索引             │
│   DiffService（已有）         Agent 改动，不改              │
└────────────────┬────────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────────┐
│ .labex/git-snapshots（bare 仓库，唯一一份）                 │
│   + 干净的 info/exclude                                    │
│   + 持久 index（用户暂存区）                                │
│   + 临时 index（快照专用，互不干扰）                         │
└────────────────┬────────────────────────────────────────┘
                 │ git http-backend（只读）
┌────────────────▼────────────────────────────────────────┐
│ 用户的 git 客户端：git clone <带 token 的 URL>             │
└─────────────────────────────────────────────────────────┘
```

## 5. 关键技术约束：索引必须隔离

`GitSnapshotService` 用**临时索引**做快照：`createEphemeralIndex()` 建临时文件，
通过 `GIT_INDEX_FILE` 环境变量传给 git 进程，用完删除。

用户暂存区需要**持久索引**（bare 仓库的 `index` 文件，当前 3.2MB）。

两者共用会互相污染：
- 快照写入临时索引后，用户暂存区的勾选状态被清空；
- 用户暂存的内容污染快照树，导致 apply/reject/undo 回滚到错误状态。

**规则**：
- 快照路径（`GitSnapshotService`）：**只用** `GIT_INDEX_FILE=<临时文件>`，永不碰持久索引。现状已如此，保持不变。
- 暂存路径（`ProjectGitService`）：**只用**持久索引，永不传 `GIT_INDEX_FILE`。
- 两条路径都通过 `WorkspaceLeaseService` 的租约锁互斥。

**必须有回归测试**：连续执行「快照 → 暂存 → 快照」，确认暂存状态不丢、快照树正确。

## 5.1 与 DeepSeek Harness `workspace-changes` 的对照

DSH 用 `@deepseek-ai/dsh-workspace-changes` 解决同类问题。读它的设计（GitHub 讨论区与
`packages/fs/workspace-changes`）能拿到三条可验证的经验，也暴露了它自己的一个缺陷。

### 它怎么做的

- **turn 前后两次快照，不是对比 HEAD**：把 git 的 object 目录指向临时目录
  （`mkdtemp` → `%TEMP%\dsh-workspace-changes-XXXXXX\objects`），在 turn 开始和结束各做一次
  `git add --all` + `write-tree`，再 `diff-tree` 求差。
- **为什么这么做**：原文说 "Uncommitted differences already present before the turn do not
  become new changes merely by being there." 对比 HEAD 的话，用户上周没提交的改动会一直
  出现在"本轮改动"里。用时间窗口划界才对。
- **文件工具与 shell 两条路**：`write`/`edit`/`str_replace_editor` 在首次编辑前整文件复制；
  shell 命令只能做路径无关快照（`rm`/`mv`/`sed -i` 可恢复，原地写入 `>`/`cp` 覆盖/`>>` 不可）。

### 它的已知缺陷（Discussion #7138）

> Concurrent user edits during the turn can still count, so the summary does not establish
> model authorship.

标题就是 *"Turn changed-files card attributes a concurrent writer's changes to this session"*——
**Agent 跑任务期间用户手改的文件，会被算成 Agent 的改动**。因为它按时间窗口归属，不按写入者归属。

### 我们的位置

| 维度 | DSH | 本项目 |
|---|---|---|
| Agent 改动归属 | 时间窗口推断，**会误判** | `t_agent_file_change` 权威记录，**不会误判** |
| 未提交改动基准 | 与 Agent 归属混用同一套快照 | 明确用 git HEAD |
| 快照隔离 | 私有 object 目录 | `--git-dir` 指向 `.labex/git-snapshots`，不碰用户仓库 |
| 仓库 git hook 风险 | Discussion #9382：`add --all` 会触发仓库 hooks | 无（我们的仓库是私有 bare 仓库） |

**结论**：归属用 change-set，不用时间窗口。这是本项目比 DSH 强的地方，不能照搬它的做法。

## 5.2 硬链接快照：实测与本轮取舍

DSH 文档提到「hardlinking every workspace file before the call takes ~0.4 s for 20,000 files,
costs no file bytes」。**本机实测达不到这个数字**，记录如下，避免照着别人的结论做决策。

| 方式 | 文件数 | 耗时 | 每文件 |
|---|---|---|---|
| `git add -A`（现状，含排除 pathspec） | 27272（含依赖） | **13678ms** | — |
| PowerShell `New-Item -ItemType HardLink` | 1556 | 11368ms | 7.3ms |
| Node `fs.linkSync`（含建目录） | 1556 | 2317ms | 1.5ms |
| Node `fs.linkSync`（平铺，排除建目录） | 1556 | 1129ms | **0.725ms** |

环境：D 盘 SSD，100MB 顺序写 176ms（IO 本身不慢）。

**差距分析**：DSH 的 0.4s/2 万文件 = 0.02ms/文件，比本机快 36 倍。这个差距不是实现方式
（已经用 Node 直接系统调用），而是**文件系统元数据操作的成本**。同一份代码在 DSH 的
开发机上快、在本机慢，说明这条数字不可移植。

**本轮结论**：**不做硬链接快照**。理由：

1. 收益有限——1556 个文件仍要 1.1 秒，而快照不是同步的热路径（Agent 改一个文件才触发一次）；
2. 硬链接快照**不能替代** git 快照：它只能恢复"被删除/被替换"的文件，无法恢复原地写入
   （`>`、`cp` 覆盖、`>>`），而 git 快照两者都覆盖。要做只能作为补充，不是替代；
3. 引入新的正确性风险（跨盘失败、硬链接与编辑器写入语义的交互），换 1 秒不值得。

**如果将来要做**，先决条件是：确认目标机器上 `fs.linkSync` 能到 0.1ms/文件以下，
否则只是把 13 秒换成 11 秒。届时作为 `GitSnapshotService` 的可选加速层，
在 `git add` 之前预筛"哪些文件没变"，而不是替代 git。

## 6. 模块设计

### 6.1 排除规则（唯一事实源）

现在有三处排除逻辑：`ProjectScanPolicy.IGNORED_DIRECTORY_NAMES`（扫描）、
`syncExclusion.js`（前端同步）、`GitSnapshotService.excludedPathspecs()`（快照）。

做法：以 `ProjectScanPolicy.IGNORED_DIRECTORY_NAMES` 为唯一来源，生成仓库的 `info/exclude`。
**不在工作区写 `.gitignore`**——那是用户的文件，会污染它；bare 仓库的 `info/exclude` 等价且不侵入。

### 6.2 历史重建（一次性脚本）

`scripts/rebuild-project-git.mjs`（运维脚本，不进业务路径）：

1. 读 `t_agent_change_set` 按 `change_set_id` 升序；
2. 每个集合取 `t_agent_file_change` 的最终内容（同路径多行取 `change_set_id` 最大的那条）；
3. 按集合顺序依次：写工作区文件 → `git add`（带排除 pathspec）→ `git commit`
   （message 用集合的时间与摘要）；
4. `delete` 类型的行执行删除；
5. 结束后 `git gc`。

**幂等**：先删旧仓库重建，重复执行结果一致。

**风险**：重建会改写工作区。开始前要求工作区干净，并备份到 `.labex/rebuild-backup-<timestamp>/`。
必须先在项目 288（22 个路径）上验证，再动项目 2。

### 6.3 后端 API

| 端点 | 用途 |
|---|---|
| `GET /student/projects/{id}/git/status` | 未提交改动（工作区 vs HEAD） |
| `POST /student/projects/{id}/git/stage` | 暂存勾选的文件（`{ paths: [] }`） |
| `POST /student/projects/{id}/git/commit` | 提交（`{ message }`） |
| `GET /student/projects/{id}/git/commits?limit=&before=` | 提交列表（分页） |
| `GET /student/projects/{id}/git/commits/{sha}` | 提交详情 + 文件清单 |
| `GET /student/projects/{id}/git/commits/{sha}/diff?path=` | 提交级 diff |
| `POST /student/projects/{id}/git/revert` | 回滚到某次提交 |
| `GET /student/projects/{id}/git/clone-url` | 带短期 token 的 clone URL |

全部走现有 JWT 鉴权 + 项目归属校验。写操作（stage / commit / revert）必须拿租约锁。

**`/git/status` 不做来源分组**：git 不区分谁改的。若 UI 要标注"这个文件 Agent 也改过"，
由前端拿 `change-set` 数据自行比对，不在 git 接口里掺 Agent 概念。

### 6.4 回滚语义

「回滚到某次提交」= 把工作区恢复到那次提交的树，**但历史不丢**：新增一条
`Revert "<目标提交摘要>"` 提交，而不是 `git reset --hard`。

理由：
- `reset --hard` 会让之后的提交从历史消失，用户以为数据丢了；
- 回滚本身应该可撤销——再回滚一次就能回去。

实现：`git read-tree` + `git checkout-index -a -f` 到工作区（不依赖 diff 文本）。

**冲突处理**：工作区有未提交改动时拒绝并提示（与同步的 strict 策略一致：不覆盖用户改动）。

### 6.5 前端 UI

**`SourceControlPanel.vue`**（源码管理）
- 未提交改动列表：状态字母（M/A/D/R）+ 路径
- 每行可勾选
- 底部：提交信息输入框 + 「提交」按钮
- 点击文件 → 展开该文件的 diff
- **可选标注**：若该路径也在当前 change-set 里，显示一个「Agent 也改过」角标
  （纯展示，不影响 git）

**`GitHistoryPanel.vue`**（提交历史）
- 提交列表：分支线 + SHA 前 7 位 + 信息 + 时间
- 点开某条：文件清单 + diff
- 「回滚到此提交」按钮，二次确认

布局：作为 `ChangesPanel` 的 tab，不新增第三栏（1180px 以下会挤）。

## 7. 测试策略

| 层 | 测什么 |
|---|---|
| 后端单测 | 排除规则生成、commit 列表分页、diff 格式、revert 冲突拒绝 |
| 后端单测（关键） | **索引隔离**：连续「快照 → 暂存 → 快照」，暂存不丢、快照树正确 |
| 后端集成 | 真实 git 仓库：重建 → stage → commit → clone → revert → 再 revert |
| 前端单测 | 两个面板的纯展示逻辑 |
| live 验收 | 项目 288 → 项目 2；`git clone` 后源码文件数 = 76 |

**关键验收判据**：
1. clone 下来的源码文件数 = **76**（与磁盘一致），不是 23153；
2. 用户手改文件 → 面板出现未提交改动 → 勾选 → 提交 → 历史可见；
3. **Agent 改文件 → 工作区出现改动、`ChangesPanel` 能看到 diff 和 undo → 不进 git 历史**；
4. 回滚到某次提交 → 新增 revert 提交，之前的历史仍在。

## 8. 不做的事

- **不做 push**：本地改动推回云端是 L3 的事，需要冲突合并策略。
- **不做分支管理**：单分支足够；提交树 UI 先画直线，为将来留位置。
- **不做行级暂存**：按文件勾选已满足需求。
- **不做自动提交**：由用户决定何时提交。
- **不改 Agent 侧**：`DiffService` / `ChangesPanel` / apply / reject / undo 全部保持原样。
- **不在 git 接口里掺 Agent 概念**：两套系统在 API 层就分开。

## 9. 实施顺序

1. 统一排除规则 + 生成 `info/exclude`（回归测试：名单与 `ProjectScanPolicy` 一致）
2. **索引隔离**：`ProjectGitService` 的 stage/commit 只用持久索引 + 隔离回归测试
3. 项目 288 验证历史重建脚本，确认 clone 文件数正确
4. 项目 2 重建，确认 76 个源码文件
5. 后端 API + 单测
6. `git http-backend` 接入 + token 鉴权
7. 前端 `SourceControlPanel` + `GitHistoryPanel` + 接线
8. live 验收：真实 clone + 用户提交 + 确认 Agent 改动不进 git

**硬链接快照不在本轮**（见 §5.2 的实测与取舍）。

## 10. 已知风险

| 风险 | 应对 |
|---|---|
| 索引互相污染（快照 vs 暂存） | 快照只用 `GIT_INDEX_FILE`，暂存只用持久索引；专门回归测试 |
| 历史重建改写工作区 | 开始前备份到 `.labex/rebuild-backup-*`，工作区脏则拒绝 |
| `git http-backend` 生产容器缺失 | 部署镜像需装 git；CI 加检查 |
| clone URL 内嵌 token 进 shell 历史 | token 短期（1 小时）+ 只读 + 按项目授权 |
| 回滚与同步并发 | 回滚、stage、commit 都走 `WorkspaceLeaseService` 同一把锁 |
| 大项目 clone 慢 | 物理成本，已在 `sync/STATUS.md` §4.3 量化 |
| 用户误以为 Agent 改动已被提交 | 面板明确显示未提交数量；`ChangesPanel` 与 git 面板并列展示 |
