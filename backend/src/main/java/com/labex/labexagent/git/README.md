# 项目版本库（Git）+ 网页源码管理面板设计

> **为什么这份说明在代码目录里**：`docs/` 整个目录被 `.gitignore` 忽略（第 75 行），
> 换机器 / 新克隆 / CI 都读不到。设计契约必须跟代码一起走。
> 同源副本在 `docs/superpowers/specs/2026-11-06-project-git-repository-design.md`（本机可见）。

**日期**：2026-11-06
**状态**：设计待评审
**相关**：`frontend/src/composables/sync/STATUS.md`、`backend/src/main/java/com/labex/labexagent/diff/GitSnapshotService.java`

## 1. 问题

用户诉求：「像 git 一样，网页端一个 git，同步到本地相当于 clone 或 push」，
并且需要「提交历史、能看 diff、能回滚」，还要能**像 VSCode 那样手动提交**（按钮、提交树）。

现状（已实测确认）：

- 云端**已经是一个真 git 仓库**：`GitSnapshotService` 在 `.labex/git-snapshots` 维护 bare 仓库。
  项目 2 有 31 条提交、745 个松散对象、1 个 pack（110KB）。
- 但它**不是给用户用的版本库**，有四个硬伤：
  1. **91.5% 是垃圾**：23153 个文件里 21035 个 `node_modules`、1897 个 `npm-cache`、
     1821 个 `dist`、141 个 `.labexagent`。根因是 2026-07-24 一条 `before shell task 52` 提交
     用了**全局快照**（`git add -A` 无排除）。排除工具目录后真正的源码只有 **56 个文件**。
  2. **内容不完整**：磁盘上有 **76 个**源码文件，仓库只有 56 个，**缺 20 个**——
     包括 `CommentController`、`CommentService`、`PostView`、`SearchPage.jsx` 等。
  3. **提交时机不可控**：`GitSnapshotService` **从不 commit**（只 `write-tree` 生成树对象）。
     那 31 条提交是 **Agent 在对话里自己跑 `git commit`** 产生的。
     所以「Agent 想起来就提交，想不起来就没有」——最后一次提交停在 7-24，而 change-set 记到 8-14。
  4. **没有对外接口**：没有任何 API 能拿到它，前端也没有 git 客户端。

**用户手改的代码完全不进版本库**：网页编辑器保存走 `PUT /files`，只写磁盘、不碰 git。

## 2. 已确认的决策

| 决策点 | 选择 |
|---|---|
| 历史处理 | **重建干净历史**，丢弃被污染的 31 条提交 |
| 提交粒度 | **按文件勾选**（VSCode 默认用法） |
| Agent 改动 | **分组展示，分别操作**（与用户改动分开） |
| 仓库位置 | **复用** `.labex/git-snapshots`，不新建第二份 |
| clone 鉴权 | URL 内嵌**短期 token** |

历史可以重建的依据：`t_agent_change_set` + `t_agent_file_change` 完整覆盖了改动历史
（项目 2 从 2026-07-10 到 2026-08-14 共 24 个集合，每个集合的 `change_count` 与实际行数一致）。

## 3. 核心设计：从「自动提交」改为「源码管理面板」

第一版设计想让系统在每次 change-set 变化后自动提交。**这个方向是错的**：
它把 git 变成"保存即提交"，用户失去对提交历史的掌控，而且提交历史会碎成几十条。

改为 git 的心智模型：**改动先进暂存区，由用户决定何时、提交哪些**。

```
┌──────────────────────────────────────────────────────────────┐
│ 网页（CloudWorkspace → 源码管理面板）                            │
│                                                              │
│  ┌─ 待提交改动 ──────────────┐  ┌─ 提交历史 ──────────────┐    │
│  │ Agent 改动（分组）          │  │ ● abc1234 修复登录  2h  │    │
│  │  ☑ M CommentController   │  │ ● def5678 初始化    1d  │    │
│  │  ☑ A SearchPage.jsx      │  │ ● 9876abc 项目骨架  3d  │    │
│  │                           │  │                        │    │
│  │ 我的改动（分组）            │  │ 点开 → 提交级 diff      │    │
│  │  ☐ M README.md           │  │ 可回滚到此提交          │    │
│  │                           │  │                        │    │
│  │ [提交信息输入框]  [提交]    │  │                        │    │
│  └───────────────────────────┘  └────────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
```

### 3.1 「Agent 改动」与「我的改动」怎么分组

两条来源不同的路径，落在同一个工作区：

| 来源 | 判定依据 | 分组 |
|---|---|---|
| Agent 写入 | 有 `t_agent_file_change` 行且状态为 `applied` | Agent 改动 |
| 用户手改 | 工作区内容与 git HEAD 不同，但没有对应的 applied change 行 | 我的改动 |

**同一文件两边都改过时归到「Agent 改动」**，因为它的内容来自 Agent 的写入；
在 UI 上标注「也包含你的修改」，避免用户误以为全是 Agent 写的。

分组只是展示层的归类，不改变 git 语义——两者都是"工作区相对 HEAD 的未提交改动"。

### 3.2 暂存区与快照索引必须隔离（关键技术约束）

`GitSnapshotService` 用**临时索引**做快照：`createEphemeralIndex()` 建临时文件，
通过 `GIT_INDEX_FILE` 环境变量传给 git 进程，用完删除。

用户暂存区需要**持久索引**（bare 仓库的 `index` 文件，当前 3.2MB）。

两者必须严格隔离，否则：
- 快照写入临时索引后，用户暂存区的勾选状态被清空；
- 或者用户暂存的内容污染快照树，导致 apply/reject/undo 回滚到错误状态。

**规则**：
- 快照路径（`GitSnapshotService`）：**只用** `GIT_INDEX_FILE=<临时文件>`，永不碰持久索引。现状已如此，保持不变。
- 暂存路径（新的 `ProjectGitService`）：**只用**持久索引，永不传 `GIT_INDEX_FILE`。
- 两条路径都通过 `WorkspaceLeaseService` 的租约锁互斥，避免同时操作工作区。

这条必须有回归测试：连续执行「快照 → 暂存 → 快照」，确认暂存状态不被破坏。

### 3.3 提交语义

- 用户勾选文件 → `git add <paths>` → 输入信息 → `git commit`
- 提交信息为空时拒绝（不生成 `WIP` 之类的噪音提交）
- 提交后工作区不变，只是 HEAD 前进
- Agent 改动与用户改动可以分开提交（分组勾选），也可以一起提交

**不做的**：行级暂存（VSCode 的 hunk 选择）。按文件勾选已满足需求，行级要让前端实现
行级 diff 选择，工作量翻倍。

## 4. 架构

```
┌─────────────────────────────────────────────────────────┐
│ 网页（CloudWorkspace → 源码管理）                           │
│   SourceControlPanel.vue（新增）                           │
│     待提交改动（分组 + 勾选）/ 提交信息 / 提交按钮            │
│   GitHistoryPanel.vue（新增）                              │
│     提交列表 / 提交级 diff / 回滚到某次提交                  │
└────────────────┬────────────────────────────────────────┘
                 │ REST（JWT）
┌────────────────▼────────────────────────────────────────┐
│ Spring Boot                                              │
│   ProjectGitController                                   │
│     /git/status    未提交改动（分组）                       │
│     /git/stage     暂存勾选的文件                           │
│     /git/commit    提交                                   │
│     /git/commits   历史列表    /git/commits/{sha}  详情     │
│     /git/diff      提交级 diff                            │
│     /git/revert    回滚到某次提交                          │
│     /git/clone-url 生成带 token 的 clone 地址              │
│   ProjectGitService         上面这些的实现                 │
│   GitSnapshotService（已有） 快照回滚，改为只用临时索引       │
└────────────────┬────────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────────┐
│ .labex/git-snapshots（bare 仓库，唯一一份）                 │
│   + 干净的 info/exclude（node_modules/dist/.labexagent…）  │
│   + 持久 index（用户暂存区）                                │
│   + 临时 index（快照专用，互不干扰）                         │
└────────────────┬────────────────────────────────────────┘
                 │ git http-backend（只读）
┌────────────────▼────────────────────────────────────────┐
│ 用户的 git 客户端：git clone <带 token 的 URL>             │
└─────────────────────────────────────────────────────────┘
```

**为什么复用同一个仓库**：快照回滚和 git 历史操作的是同一份代码树。分成两个仓库会双写、
磁盘占用翻倍，而且两边状态可能不一致——回滚后 git 历史里还是旧内容。

**为什么 clone 走 `git http-backend`**：git 传输协议（packfile 协商、capability 广告）
自己实现必然出错。它是 git 自带的 CGI，本机已确认可用
（`D:\Git\mingw64\libexec\git-core\git-http-backend.exe`，git 2.50.0）。

## 5. 模块设计

### 5.1 排除规则（唯一事实源）

现在有两套排除规则：`ProjectScanPolicy.IGNORED_DIRECTORY_NAMES`（扫描用）和
`syncExclusion.js`（前端同步用），`GitSnapshotService.excludedPathspecs()` 是第三处。

做法：以 `ProjectScanPolicy.IGNORED_DIRECTORY_NAMES` 为唯一来源，生成仓库的 `info/exclude`。
**不在工作区写 `.gitignore`**——那是用户的文件，我们会污染它；bare 仓库的 `info/exclude` 等价且不侵入。

### 5.2 历史重建（一次性脚本）

`scripts/rebuild-project-git.mjs`（运维脚本，不进业务路径）：

1. 读 `t_agent_change_set` 按 `change_set_id` 升序；
2. 每个集合取 `t_agent_file_change` 的最终内容（同路径多行取 `change_set_id` 最大的那条）；
3. 按集合顺序依次：写工作区文件 → `git add`（带排除 pathspec）→ `git commit`
   （message 用集合的时间与变更摘要）；
4. 对 `delete` 类型的行执行删除；
5. 结束后 `git gc`。

**幂等**：先删掉旧仓库重建，重复执行结果一致。

**风险**：重建期间工作区内容会被改写。脚本必须先要求工作区干净，
并在开始前把当前工作区备份到 `.labex/rebuild-backup-<timestamp>/`。
实施时必须先在项目 288（22 个路径）上验证，再动项目 2。

### 5.3 后端 API

| 端点 | 用途 |
|---|---|
| `GET /student/projects/{id}/git/status` | 未提交改动，按来源分组（agent / user） |
| `POST /student/projects/{id}/git/stage` | 暂存勾选的文件（`{ paths: [] }`） |
| `POST /student/projects/{id}/git/commit` | 提交（`{ message }`，提交已暂存内容） |
| `GET /student/projects/{id}/git/commits?limit=&before=` | 提交列表（分页） |
| `GET /student/projects/{id}/git/commits/{sha}` | 单次提交详情 + 文件清单 |
| `GET /student/projects/{id}/git/commits/{sha}/diff?path=` | 提交级 diff |
| `POST /student/projects/{id}/git/revert` | 回滚到某次提交 |
| `GET /student/projects/{id}/git/clone-url` | 生成带短期 token 的 clone URL |

全部走现有 JWT 鉴权 + 项目归属校验（`requireOwnedProject`）。
写操作（stage / commit / revert）必须拿 `WorkspaceLeaseService` 的租约锁。

### 5.4 回滚语义

「回滚到某次提交」= 把工作区恢复到那次提交的树，**但历史不丢**：新增一条
`Revert "<目标提交摘要>"` 提交，而不是 `git reset --hard`。

理由：
- `reset --hard` 会让之后的提交从历史里消失，用户以为数据丢了；
- 回滚本身应该可撤销——再回滚一次就能回去。

实现：`git read-tree` + `git checkout-index -a -f` 到工作区（不依赖 diff 文本，更可靠）。

**冲突处理**：工作区有未提交改动时拒绝并提示（与同步的 strict 策略一致：不覆盖用户改动）。

### 5.5 前端 UI

两个新组件，接在审查页：

**`SourceControlPanel.vue`**（源码管理）
- 两个分组：「Agent 的改动」「我的改动」，各自带勾选框
- 每行：状态字母（M/A/D/R）+ 路径 + 行数统计
- 底部：提交信息输入框 + 「提交」按钮
- 点击文件 → 展开该文件的 diff（复用 `ChangesPanel` 的 diff 渲染）

**`GitHistoryPanel.vue`**（提交历史）
- 提交列表：图形式分支线（单分支时是直线）+ SHA 前 7 位 + 信息 + 时间
- 点开某条：文件清单 + 每个文件的 diff
- 「回滚到此提交」按钮，二次确认

布局：`ChangesPanel` 现在是审查页左栏。源码管理与提交历史作为它的 tab，
不新增第三栏（1180px 以下会挤）。

## 6. 测试策略

| 层 | 测什么 |
|---|---|
| 后端单测 | 排除规则生成、分组判定、commit 列表分页、diff 格式、revert 冲突拒绝 |
| 后端单测（关键） | **索引隔离**：连续「快照 → 暂存 → 快照」，暂存状态不丢、快照树正确 |
| 后端集成 | 真实 git 仓库：重建 → stage → commit → clone → revert → 再 revert |
| 前端单测 | 两个面板的纯展示逻辑（列表映射、分组、diff 解析复用） |
| live 验收 | 项目 288（小）→ 项目 2（真实）；`git clone` 后源码文件数 = 76 |

**关键验收判据**：
1. clone 下来的源码文件数 = **76**（与磁盘一致），不是 23153；
2. 用户手改一个文件 → 面板出现「我的改动」→ 勾选 → 提交 → 历史里能看见；
3. Agent 改一个文件 → 面板出现「Agent 的改动」→ 与用户改动能分开提交。

## 7. 不做的事

- **不做 push**：用户本地改动推回云端是 L3 的事，需要冲突合并策略。
- **不做分支管理**：单分支足够；创建/切换分支是 L2+ 的复杂度。提交树 UI 仍画分支线，为将来留位置。
- **不做行级暂存**：按文件勾选已满足需求。
- **不做自动提交**：改由用户决定何时提交。若要自动，后续加开关。
- **不改 `GitSnapshotService` 的回滚语义**：它服务于 apply/reject/undo 的精确回滚，
  和面向用户的 git 回滚是两件事，共用一个仓库但索引隔离。

## 8. 实施顺序

1. 统一排除规则 + 生成 `info/exclude`（回归测试：名单与 `ProjectScanPolicy` 一致）
2. **索引隔离**：`ProjectGitService` 的 stage/commit 只用持久索引 + 隔离回归测试
3. 在项目 288 上验证历史重建脚本，确认 clone 文件数正确
4. 项目 2 重建，确认 76 个源码文件
5. 后端 API（status / stage / commit / commits / diff / revert / clone-url）+ 单测
6. `git http-backend` 接入 + token 鉴权
7. 前端 `SourceControlPanel` + `GitHistoryPanel` + 接线
8. live 验收：真实 `git clone` 一次 + 用户提交一次 + Agent 提交一次

## 9. 已知风险

| 风险 | 应对 |
|---|---|
| 索引互相污染（快照 vs 暂存） | 快照只用 `GIT_INDEX_FILE`，暂存只用持久索引；专门回归测试 |
| 历史重建改写工作区，可能丢用户未提交改动 | 开始前备份到 `.labex/rebuild-backup-*`，工作区脏则拒绝执行 |
| `git http-backend` 在生产容器里可能缺失 | 部署镜像需装 git；CI 加检查（`git http-backend --help` 可用） |
| clone URL 内嵌 token 会进 shell 历史 | token 短期（1 小时）+ 只读 + 按项目授权；文档说明风险 |
| 回滚与同步并发 | 回滚、stage、commit 都走 `WorkspaceLeaseService` 同一把锁，与 apply 互斥 |
| 大项目 clone 慢 | 物理成本（逐文件检出），已在 `sync/STATUS.md` §4.3 量化；clone 不比同步慢 |
| Agent 与用户改同一文件，分组归属可能误导 | UI 标注「也包含你的修改」，并在提交前展示完整 diff |
