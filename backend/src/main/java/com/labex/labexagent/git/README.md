# 项目版本库（Git）+ 网页 Git UI 设计

> **为什么这份说明在代码目录里**：`docs/` 整个目录被 `.gitignore` 忽略（第 75 行），
> 换机器 / 新克隆 / CI 都读不到。设计契约必须跟代码一起走。
> 同源副本在 `docs/superpowers/specs/2026-11-06-project-git-repository-design.md`（本机可见）。

**日期**：2026-11-06
**状态**：设计待评审
**相关**：`frontend/src/composables/sync/STATUS.md`、`backend/src/main/java/com/labex/labexagent/diff/GitSnapshotService.java`

## 1. 问题

用户提出的诉求：「像 git 一样，网页端一个 git，同步到本地相当于 clone 或 push，直接同步或拉取」，
并且需要「提交历史、能看 diff、能回滚」的 git UI。

现状（已实测确认，不是推测）：

- 云端**已经是一个真 git 仓库**：`GitSnapshotService` 在 `.labex/git-snapshots` 维护 bare 仓库。
  项目 2 有 31 条提交、745 个松散对象、1 个 pack（110KB）。
- 但它**不是给用户用的版本库**，有三个硬伤：
  1. **91.5% 是垃圾**：23153 个文件里 21035 个是 `node_modules`、1897 个 `npm-cache`、
     1821 个 `dist`、141 个 `.labexagent`。根因是 2026-07-24 一条 `before shell task 52` 提交
     用了**全局快照**（`git add -A` 无排除），把 `node_modules` 一次带进历史，
     之后 6 条提交都背着它。排除工具目录后真正的源码只有 **56 个文件**。
  2. **内容不完整**：磁盘上有 **76 个**源码文件，仓库只有 56 个，**缺 20 个**——
     包括 `CommentController`、`CommentService`、`PostView`、`SearchPage.jsx` 等。
     最后一次提交停在 2026-07-24，而项目 2 的 change-set 记到 2026-08-14。
     它只在 patch batch / shell task 路径提交，不是所有改动都进。
  3. **没有对外接口**：没有任何 API 能拿到它，前端也没有 git 客户端。
- 用户无法 clone，也无法在网页看提交历史。

## 2. 已确认的决策

| 决策点 | 选择 |
|---|---|
| 历史处理 | **重建干净历史**，丢弃被污染的 31 条提交 |
| 网页 UI 范围 | 提交历史 + 提交级 diff + **回滚到某次提交** |
| 仓库位置 | **复用** `.labex/git-snapshots`，不新建第二份 |
| clone 鉴权 | URL 内嵌**短期 token** |

历史可以重建的依据：`t_agent_change_set` + `t_agent_file_change` 完整覆盖了改动历史
（项目 2 从 2026-07-10 到 2026-08-14 共 24 个集合，每个集合的 `change_count` 与实际行数一致）。
重建不依赖那个被污染的快照仓库。

## 3. 架构

```
┌─────────────────────────────────────────────────────────┐
│ 网页（CloudWorkspace 审查页）                              │
│   ChangesPanel（已有：单文件 diff / undo）                  │
│   GitHistoryPanel（新增：提交列表 / 提交级 diff / 回滚）      │
└────────────────┬────────────────────────────────────────┘
                 │ REST（JWT）
┌────────────────▼────────────────────────────────────────┐
│ Spring Boot                                              │
│   ProjectGitController      /git/commits  /git/diff       │
│                             /git/revert   /git/clone-url  │
│   ProjectGitService         读仓库、算 diff、执行回滚       │
│   GitSnapshotService（已有） 快照回滚，保持不变             │
└────────────────┬────────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────────┐
│ .labex/git-snapshots（bare 仓库，唯一一份）                 │
│   + 干净的 exclude（node_modules/dist/.labexagent…）       │
│   + 每次 apply 后自动 commit（补上现在的缺口）              │
└────────────────┬────────────────────────────────────────┘
                 │ git http-backend（只读）
┌────────────────▼────────────────────────────────────────┐
│ 用户的 git 客户端：git clone <带 token 的 URL>             │
└─────────────────────────────────────────────────────────┘
```

**为什么复用同一个仓库**：快照回滚和 git 历史操作的是同一份代码树。分成两个仓库会双写、
磁盘占用翻倍，而且两边状态可能不一致——回滚后 git 历史里还是旧内容。

**为什么 clone 走 `git http-backend` 而不是自己实现协议**：git 的传输协议（packfile 协商、
capability 广告）自己实现必然出错。`git http-backend` 是 git 自带的 CGI，本机已确认可用
（`D:\Git\mingw64\libexec\git-core\git-http-backend.exe`，git 2.50.0）。

## 4. 模块设计

### 4.1 排除规则（唯一事实源）

现在有两套排除规则：`ProjectScanPolicy.IGNORED_DIRECTORY_NAMES`（扫描用）和
`syncExclusion.js`（前端同步用），`GitSnapshotService.excludedPathspecs()` 是第三处。
重建历史时必须统一。

做法：以 `ProjectScanPolicy.IGNORED_DIRECTORY_NAMES` 为唯一来源，生成仓库的 `info/exclude`。
**不在工作区写 `.gitignore`**——那是用户的文件，我们会污染它；bare 仓库的 `info/exclude` 等价且不侵入。

### 4.2 历史重建（一次性脚本）

`scripts/rebuild-project-git.mjs`（运维脚本，不进业务路径）：

1. 读 `t_agent_change_set` 按 `change_set_id` 升序；
2. 每个集合取 `t_agent_file_change` 的最终内容（同路径多行取 `change_set_id` 最大的那条）；
3. 按集合顺序依次：写工作区文件 → `git add`（带排除 pathspec）→ `git commit`
   （message 用集合的时间与变更摘要）；
4. 对 `delete` 类型的行执行删除；
5. 结束后 `git gc`。

**幂等**：先删掉旧仓库重建，重复执行结果一致。

**风险**：重建期间工作区内容会被改写。脚本必须先 `git stash` 或要求工作区干净，
并在开始前把当前工作区备份到 `.labex/rebuild-backup-<timestamp>/`。
实施时必须先在项目 288（22 个路径）上验证，再动项目 2。

### 4.3 自动提交（补上现在的缺口）

现状：只有 patch batch / shell task 提交。用户手改的代码（`PUT /files`）不提交，
所以 clone 下来会缺东西。

改为：**每次 change-set 状态变化后提交一次**（apply / reject / undo / 手改保存）。
复用 `AgentChangeSet` 的生命周期，不新造状态。

提交信息格式：`<changeSetId>: <摘要>`，摘要取 change-set 的 `summary` 字段，
为空时用「N 个文件变更」。

### 4.4 后端 API

| 端点 | 用途 |
|---|---|
| `GET /student/projects/{id}/git/commits?limit=&before=` | 提交列表（分页） |
| `GET /student/projects/{id}/git/commits/{sha}` | 单次提交详情 + 文件清单 |
| `GET /student/projects/{id}/git/commits/{sha}/diff?path=` | 提交级 diff（复用现有 diff 渲染格式） |
| `POST /student/projects/{id}/git/revert` | 回滚到某次提交 |
| `GET /student/projects/{id}/git/clone-url` | 生成带短期 token 的 clone URL |

全部走现有 JWT 鉴权 + 项目归属校验（`requireOwnedProject`）。

### 4.5 回滚语义（最容易出错的地方）

「回滚到某次提交」= 把工作区恢复到那次提交的树，**但历史不丢**：新增一条
`Revert "<目标提交摘要>"` 提交，而不是 `git reset --hard`。

理由：
- `reset --hard` 会让之后的提交从历史里消失，用户以为数据丢了；
- 回滚本身应该可撤销——再回滚一次就能回去。

实现：`git read-tree` + `git checkout-index` 到工作区，或直接
`git diff <target> HEAD | git apply -R`。选前者（更可靠，不依赖 diff 文本）。

**冲突处理**：如果工作区有未提交改动，回滚前必须拒绝并提示
（与同步的 strict 策略一致：不覆盖用户的本地改动）。

### 4.6 前端 UI

`GitHistoryPanel.vue`，接在审查页的 `.review-split` 里（现在左边 ChangesPanel、右边 SyncPanel，
历史面板作为 ChangesPanel 的第二个 tab，不新增第三栏——1180px 以下会挤）。

内容：
- 提交列表：SHA 前 7 位、时间、摘要、文件数；
- 点开某条：该次提交的文件清单 + 每个文件的 diff（复用 `ChangesPanel` 的 diff 渲染）；
- 「回滚到此提交」按钮，二次确认，说明会新增一条 revert 提交。

## 5. 测试策略

| 层 | 测什么 |
|---|---|
| 后端单测 | 排除规则生成、commit 列表分页、diff 格式、revert 的冲突拒绝 |
| 后端集成 | 真实 git 仓库上跑：重建 → clone → revert → 再 revert |
| 前端单测 | `GitHistoryPanel` 的纯展示逻辑（列表映射、diff 解析复用） |
| live 验收 | 项目 288（小）→ 项目 2（真实）；`git clone` 后源码文件数 = 76 而非 23153 |

**关键验收判据**：clone 下来的源码文件数必须是 **76**（与磁盘一致），不是 23153。
这条直接证明排除规则生效。

## 6. 不做的事

- **不做 push**：用户本地改动推回云端是 L3 的事，需要冲突合并策略，本轮不做。
- **不做分支**：单分支（master/main）足够；分支管理是 L2+ 的复杂度。
- **不做网页端提交**：提交由 change-set 自动产生，用户不需要手动 commit。
- **不改 `GitSnapshotService` 的回滚语义**：它服务于 apply/reject/undo 的精确回滚，
  和面向用户的 git 回滚是两件事，共用一个仓库但互不干扰。

## 7. 实施顺序

1. 统一排除规则 + 生成 `info/exclude`（含回归测试：排除名单与 `ProjectScanPolicy` 一致）
2. 在项目 288 上验证历史重建脚本，确认 clone 文件数正确
3. 项目 2 重建，确认 76 个源码文件（当前仓库只有 56 个，缺 20 个）
4. 后端 API（commits / diff / revert / clone-url）+ 单测
5. `git http-backend` 接入 + token 鉴权
6. 前端 `GitHistoryPanel` + 接线
7. live 验收：真实 `git clone` 一次，比对文件数

## 8. 已知风险

| 风险 | 应对 |
|---|---|
| 历史重建改写工作区，可能丢用户未提交改动 | 开始前备份到 `.labex/rebuild-backup-*`，工作区脏则拒绝执行 |
| `git http-backend` 在生产容器里可能缺失 | 部署镜像需装 git；CI 加一条检查（`git http-backend --help` 可用） |
| clone URL 内嵌 token 会进 shell 历史 | token 短期（1 小时）+ 只读 + 按项目授权；文档说明风险 |
| 回滚与同步并发 | 回滚走 `WorkspaceLeaseService` 的同一把租约锁，与 apply 互斥 |
| 大项目 clone 慢 | 这是物理成本（逐文件检出），已在 `sync/STATUS.md` §4.3 量化；clone 不比同步慢 |
