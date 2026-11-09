# 项目版本库（Git）+ 源码管理面板设计

> **为什么这份说明在代码目录里**：`docs/` 整个目录被 `.gitignore` 忽略（第 75 行），
> 换机器 / 新克隆 / CI 都读不到。设计契约必须跟代码一起走。
> 同源副本在 `docs/superpowers/specs/2026-11-06-project-git-repository-design.md`（本机可见）。

**日期**：2026-11-06
**状态**：已实施（见 §11 实施记录）
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

`GitSnapshotService` 用**独立索引**做快照，用户暂存区用**仓库默认索引**。

两者共用会互相污染：
- 快照写入同一索引后，用户暂存区的勾选状态被清空；
- 用户暂存的内容污染快照树，导致 apply/reject/undo 回滚到错误状态。

**规则**：
- 快照路径（`GitSnapshotService`）：**只用** `GIT_INDEX_FILE=<仓库>/snapshot.index`，永不碰默认索引。
- 暂存路径（`ProjectGitService`）：**只用**默认索引，永不传 `GIT_INDEX_FILE`。
- 两条路径都通过 `WorkspaceLeaseService` 的租约锁互斥。

**必须有回归测试**：连续执行「快照 → 暂存 → 快照」，确认暂存状态不丢、快照树正确。
测试见 `ProjectGitIndexIsolationTest`。

### 5.0 快照索引必须是持久文件（实施期修正）

原设计写的是「临时索引，用完删除」。实测推翻了这个做法：

| 索引方式 | 10k 文件首次 | 10k 文件后续 |
|---|---|---|
| 专用持久索引 | 53.0s | **300ms / 492ms** |
| 临时索引（用完就删） | 35.9s | **39.3s / 40.5s** |

原因：git 的 stat 缓存存在索引文件里。临时索引每次都是空的，`git add` 必须重新哈希全部
文件内容；持久索引只哈希变了的文件。10k 文件从 40 秒降到 0.5 秒，**80 倍**，且随文件数线性放大
（10 万文件时临时索引约 7 分钟）。

所以快照索引是**持久的独立文件** `snapshot.index`，隔离靠「不同文件」而不是「用完就删」。
它不属于任何提交、也不被 `git status` 读取，因此不会污染用户可见状态。

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

### 6.2 旧仓库重置（一次性脚本）

`scripts/project-git-reset.mjs`（运维脚本，不进业务路径）：把被污染的
`.labex/git-snapshots` 改名备份到 `.labex/git-snapshots-backup-<timestamp>/`，**工作区文件一个不动**。
之后用户在前端面板勾选文件、写提交信息，完成第一次提交。

```bash
node scripts/project-git-reset.mjs --project=288          # 预演
node scripts/project-git-reset.mjs --project=288 --apply  # 执行
node scripts/project-git-reset.mjs --all --apply
```

**幂等**：仓库已不存在时报告「无需处理」并退出 0，重复执行结果一致。

**为什么不是「按 change-set 回放重建历史」**：初稿写的回放方案与 §1「Agent 改动不进 git」
直接冲突——它会把 45 条 Agent 改动变成 5 条 git 提交，正是本文档禁止的事。历史重建只能
丢弃脏仓库，不能伪造一条由 Agent 改动组成的提交线。

### 6.3 后端 API

| 端点 | 用途 |
|---|---|
| `GET /student/projects/{id}/git/status` | 未提交改动（工作区 vs HEAD） |
| `POST /student/projects/{id}/git/stage` | 暂存勾选的文件（`{ paths: [] }`） |
| `POST /student/projects/{id}/git/commit` | 提交（`{ message }`） |
| `GET /student/projects/{id}/git/commits?limit=&before=` | 提交列表（分页） |
| `GET /student/projects/{id}/git/commits/{sha}` | 提交详情 + 文件清单 |
| `GET /student/projects/{id}/git/commits/{sha}/diff?path=` | 提交级 diff |
| `GET /student/projects/{id}/git/diff?path=` | 未提交改动的单文件 diff |
| `POST /student/projects/{id}/git/revert` | 回滚到某次提交 |
| `GET /student/projects/{id}/git/clone-url` | 带短期 token 的 clone URL |

全部走现有 JWT 鉴权 + 项目归属校验。写操作（stage / commit / revert）必须拿租约锁。

**clone 端点不在 `/api` 之下**：`/git/{projectId}.git` 挂在根路径。原因是 git 客户端不经过
前端代理，且 `git clone` 的 URL 会被用户直接粘到终端；`/api/git/2.git` 这种路径会让人误以为
要通过前端。该路径在 `SecurityConfig` 里 `permitAll`，安全性完全由短期令牌承担
（`ProjectGitCloneTokenService`：HMAC-SHA256、只读、限定单个项目、默认 1 小时）。

**`/git/status` 不做来源分组**：git 不区分谁改的。若 UI 要标注"这个文件 Agent 也改过"，
由前端拿 `change-set` 数据自行比对，不在 git 接口里掺 Agent 概念。

### 6.4 回滚语义

「回滚到某次提交」= 把工作区恢复到那次提交的树，**但历史不丢**：新增一条
`Revert "<目标提交摘要>"` 提交，而不是 `git reset --hard`。

理由：
- `reset --hard` 会让之后的提交从历史消失，用户以为数据丢了；
- 回滚本身应该可撤销——再回滚一次就能回去。

实现：`git read-tree` + `git checkout-index -a -f` 到工作区（不依赖 diff 文本）。

**实际实现**：`git read-tree --reset -u <target>` 一步同时更新索引与工作区。比
`checkout-index` 少一步，语义相同（都是「让工作区等于目标树」），且不会漏掉删除的文件。

**Windows 陷阱**：提交信息必须用 `git commit --file <临时文件>`，不能用 `git commit -m <message>`。
ProcessBuilder 在 Windows 上对含内嵌双引号的参数转义不完整，`Revert "abc"` 会被拆成多个 argv，
git 把后半段当 pathspec 报 `did not match any file(s) known to git`。回归测试见
`ProjectGitServiceTest#commitMessageWithQuotesAndColonsSurvivesIntact`。

**冲突处理**：工作区有未提交改动时拒绝并提示（与同步的 strict 策略一致：不覆盖用户改动）。

### 6.5 前端 UI

面板**放在侧边栏**，与「文件 / 会话 / 搜索」并列，活动栏上有独立入口（带待提交数量角标）。
不放在 AI 审查页：VSCode 把源代码管理放在活动栏，用户在左边找它；塞进 AI 面板的三个 tab
里既违背直觉，也和「Agent 改动」抢同一块空间。

**`SourceControlPanel.vue`**（复刻 VSCode 源代码管理）
- 头部：标题 + `master` 分支 chip（`git-branch` 图标）+ 刷新 / clone 两颗图标按钮
- 提交信息输入框在**上方**，主提交按钮紧贴其下，右侧是「全选 / 取消全选」按钮
  - 按钮默认是**描边幽灵态**（不可提交时只是不可点，不是一块灰块）；输入了信息且勾选了文件
    才整组变成实心强调色。旧版禁用态是一整块灰底，看起来像坏掉的控件
- 「更改」可折叠分组，标题带 `file-diff` 图标，右侧是改动数量角标
- 文件行：**文件类型图标**（复用 `components/icons/FileIcon.vue`，与文件树同一套 vscode-icons）
  + **文件名在前** + 目录弱化在后（`direction:rtl` 从尾部截断，保留离文件名最近的父目录）
  + 状态方块（带语义色底）+ hover 时出现的打开箭头
- 点文件名 → **在中栏打开 diff**（见 §6.5.1），侧边栏不就地展开
- **默认全选**：点一下提交即提交全部改动（对齐 VSCode 的 smart commit）；
  用户取消勾选后不再自动加回
- 若该路径也在当前 change-set 里，显示「Agent 也改过」角标（纯展示，不影响 git）
- 空状态：`circle-check` 图标 + 一句结论（「没有未提交的改动」/「还没有提交历史」）+ 一句说明，
  不再是单行灰字
- clone 地址**默认折叠**：令牌一小时过期，提前拉没有意义；展开时才请求，复制按钮带文字反馈

**`GitGraphSection.vue`**（图表，内嵌在同一个面板的「图表」分组）
- 竖线串节点，HEAD 节点实心带光晕，最新提交带分支标签（`master`）
- **按天分组**：分组标题承担日期，行内只留时分；一天提十几条时「哪天做的」不再被相对时间淹没
  （`groupCommitsByDay` / `formatCommitTime` 早已存在，此前无人调用）
- 点开某条：sha / 作者 / 完整时间 + 文件清单（类型图标、状态方块、增删行数），
  点文件同样在中栏打开该提交的 diff；展开有 140ms 的位移淡入
- 「回滚到此提交」按钮，二次确认

配色走 `--theme-*` 变量 + `html[data-theme="dark"]` 覆盖，与侧边栏其余面板同一套主题语言。
初版硬编码深色值，浅色主题下几乎不可读，已修正。图标一律用 `~icons/lucide/*` 按需引入
（`unplugin-icons` 在构建期编译为内联 SVG），不在模板里手写路径。

**旧位置**：早先版本把 git 放在 AI 审查页的三个 tab 里（Agent 改动 / 源代码管理 / 提交历史）。
审查页现在只剩 `ChangesPanel`（Agent 改动）与同步面板——这两套系统本来就不该共用入口。

### 6.5.1 diff 在中栏展示，复用 `CenterDiffViewer`

侧边栏只有 260px，diff 在那儿既看不清，展开还会把「更改」列表挤走。改成 VSCode 的分工：
**列表在左、diff 在右**。点「更改」里的文件或图表里的文件 → 中栏开一个只读 diff 标签。

复用已有的 `components/cloud/CenterDiffViewer.vue`（Agent 改动也是它渲染），新增两个属性：

| 属性 | 作用 |
|---|---|
| `show-actions` | 默认 `true`。git diff 是只读的，传 `false` 隐藏「保留 / 回退 / 编辑源码」——这三颗按钮对 git 没有语义 |
| `loading` | 读取中显示「正在读取 diff…」，否则空 diff 会闪一下「暂无该文件的代码变动对比」 |

**标签身份用合成路径**：`真实路径::git`（提交级再加 `@<sha>`）。原因：`openFile` 按 `path`
复用已有标签，若 git diff 标签直接用真实路径，点文件树会激活 diff 而不是编辑器。
展示用的路径另存在 `gitPath`，`applyActiveFile` 取 `file.gitPath || file.path`，
所以面包屑和 AI 输入框看到的仍是真实路径。

**再点一次同一个文件 = 关闭**（对齐 VSCode 的 toggle）。

**顺带修掉的渲染缺陷**（同一组件，Agent 改动也受益）：
- 文件级元信息行（`new file mode` / `index 0000..111` / `Binary files ...`）曾被当成内容行，
  顶着行号出现在正文第一行。新增 `isDiffHeaderLine()` 统一跳过。
- 深色主题下增删行「另一侧」的行号格没有覆盖背景，在深色面板里是一块白斑。

**删除**：`components/git/DiffLines.vue` 与 `sourceControlModel.diffToLines()`——它们只服务于
侧边栏就地展开的那套渲染，改为复用 `CenterDiffViewer` 后已无调用方。

### 6.6 还没有第一条提交时

面板在全新项目上第一次打开就同时问三件事：有哪些改动、历史是什么、这个文件改了什么。此时仓库
刚由 `git init --bare` 建出来，`master` 指向一个未出生的分支——HEAD 文件存在，但它指向的引用没有值。
git 对这三种查询的反应各不相同，必须逐条按「没有基线」处理：

| 查询 | 直接跑 git 的结果 | 面板行为 |
|---|---|---|
| `status --porcelain` | 正常，全部文件是 `??` | 列出文件，`initialized=false` |
| `log` | `fatal: your current branch 'master' does not have any commits yet` | 返回空列表 |
| `diff HEAD -- <path>` | `fatal: bad revision 'HEAD'` | 按新增文件出 diff |

判断依据统一用 `ProjectGitRepository.hasCommits`（`rev-parse --verify HEAD`），**不要**用「HEAD
文件是否存在」——那只说明仓库建过，不说明有历史。

**未跟踪文件属于同一类**：`git diff HEAD -- <path>` 对未跟踪文件什么都不输出（它不在索引里），
所以首次提交之后新加的文件同样只会看到空 diff。这类文件改走
`git diff --no-index --patch -- /dev/null <path>`，整份内容按新增输出。`--no-index`
在发现差异时退出码为 1，那是命令的正常结果；`GitCommandRunner.gitDiffNoIndex` 只额外接受这一个
退出码，其余非零仍然抛异常。

diff 一律不传 `--binary`：二进制文件只显示 `Binary files ... differ` 一行，否则整段 base64
补丁会铺满面板（229 个文件的首次提交里就含 `salary.db`、`salary_model.pkl`）。

## 7. 测试策略

| 层 | 测什么 | 实现 |
|---|---|---|
| 后端单测 | 排除规则与扫描名单一致 | `ProjectGitExcludeTest`（3） |
| 后端单测 | porcelain / name-status / numstat 解析 | `GitPorcelainParserTest`（8） |
| 后端单测（关键） | **索引隔离**：快照不碰暂存索引 | `ProjectGitIndexIsolationTest`（3） |
| 后端单测 | status / stage / commit / 分页 / revert | `ProjectGitServiceTest`（18） |
| 后端单测 | 首次提交前：历史为空、未跟踪/二进制文件出 diff | `ProjectGitServiceTest`（§6.6） |
| 后端单测 | clone 令牌签发、校验、篡改、过期、撤销 | `ProjectGitCloneTokenServiceTest`（6） |
| 前端单测 | 面板纯展示逻辑（状态字母/全称、路径拆分、默认全选、clone 命令、分支常量） | `git-source-control-model.test.mjs`（14） |
| live 验收 | 真实 clone + 用户提交 + Agent 改动不进 git | 见 §11 |

**关键验收判据**：
1. clone 下来的源码文件数 = **77**（与磁盘一致），不是 23153；
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

## 9. 实施顺序（已全部完成）

1. 统一排除规则 + 生成 `info/exclude`（回归测试：名单与 `ProjectScanPolicy` 一致）
2. **索引隔离**：`ProjectGitService` 的 stage/commit 只用默认索引 + 隔离回归测试
3. 项目 288 验证重置脚本，确认快照树只含 24 个源码文件
4. 项目 2 重置，确认快照树含 77 个源码文件
5. 后端 API + 单测
6. `git http-backend` 接入 + token 鉴权
7. 前端 `SourceControlPanel` + `GitHistoryPanel` + 接线
8. live 验收：真实 clone + 用户提交 + 确认 Agent 改动不进 git

**硬链接快照不在本轮**（见 §5.2 的实测与取舍）。

## 10. 已知风险

| 风险 | 应对 |
|---|---|
| 索引互相污染（快照 vs 暂存） | 快照只用 `snapshot.index`，暂存只用默认索引；专门回归测试 |
| 旧仓库重置丢历史 | 改名备份到 `.labex/git-snapshots-backup-*`，工作区不动；脚本幂等 |
| `git http-backend` 生产容器缺失 | 部署镜像需装 git；缺失时端点返回 503 并记 error 日志 |
| clone URL 内嵌 token 进 shell 历史 | token 短期（1 小时）+ 只读 + 按项目授权 |
| 回滚与同步并发 | 回滚、stage、commit 都走 `WorkspaceLeaseService` 同一把锁 |
| 大项目 clone 慢 | 物理成本，已在 `sync/STATUS.md` §4.3 量化 |
| 用户误以为 Agent 改动已被提交 | 面板明确显示未提交数量；`ChangesPanel` 与 git 面板并列展示 |

## 11. 实施记录

### 11.1 后端与首版前端（2026-11-06）

**代码位置**

| 模块 | 文件 |
|---|---|
| git 子进程唯一入口 | `git/GitCommandRunner`（超时、输出上限、`GIT_INDEX_FILE` 注入） |
| 仓库位置与初始化 | `git/ProjectGitRepository`（`info/exclude` 生成、配置缓存） |
| 用户提交 | `git/ProjectGitService`（status / stage / commit / commits / detail / diff / revert） |
| 输出解析 | `git/GitPorcelainParser`（纯函数，无 IO） |
| clone 鉴权 | `git/ProjectGitCloneTokenService`、`git/ProjectGitHttpBackend` |
| REST | `controller/student/ProjectGitController`（`/api/student/projects/{id}/git/**`） |
| clone 端点 | `controller/student/ProjectGitHttpController`（`/git/{id}.git`） |
| 配置 | `git/ProjectGitProperties`（`labex-agent.project-git.*`） |

**实测结果**

| 项 | 结果 |
|---|---|
| 项目 2 快照树文件数 | **77**（重置前被污染的仓库是 23153，源码只有 56） |
| 此前缺失的文件 | `CommentController`、`CommentService`、`PostView`、`SearchPage.jsx` 等 20 个已全部收录 |
| 真实 `git clone` | 成功，77 个文件 |
| Agent 改动后 git 历史 | 不变（改动只出现在工作区未提交列表） |
| 回滚 | 新增 `Revert "..."` 提交，历史全部保留 |
| 项目 2 重置备份 | 124.9 MB（`.labex/git-snapshots-backup-2026-11-06T09-52-17-692Z`） |

**实施期修正的两处设计**

1. **快照索引用持久文件而非临时文件**（§5.0）：10k 文件实测 40s → 0.5s。
2. **旧仓库处理是「重置 + 用户首次提交」，不是「按 change-set 回放重建历史」**（§6.2）：
   回放与「Agent 改动不进 git」自相矛盾。

### 11.2 面板移入侧边栏 + 复刻 VSCode（2026-11-09）

首版把 git 放在 AI 审查页的三个 tab 里。改成侧边栏独立视图，并按 VSCode 重排信息层级。

**前端结构**

| 模块 | 文件 |
|---|---|
| 活动栏入口 + 待提交角标 | `CloudWorkspace.vue`（`rail-btn` + `rail-badge`）、`styles/cloud-workspace.scoped.scss` |
| 侧边栏视图切换 | `components/sidebar/SidebarNav.vue`（新增「代码」） |
| 源代码管理面板 | `components/git/SourceControlPanel.vue` + `styles/source-control-panel.scss` |
| 图表分组 | `components/git/GitGraphSection.vue` + `styles/git-graph-section.scss` |
| 纯展示逻辑 | `composables/git/sourceControlModel.js`（新增 `splitGitPath` / `canCommit` / `defaultSelection`） |

**删除**：`GitHistoryPanel.vue`、`styles/git-history-panel.scss`、`styles/git-panels.scss`
（提交历史并入面板内的「图表」分组，不再单独成页）。

**浏览器实测**（项目 2，浅色 + 深色主题各一遍）

| 项 | 结果 |
|---|---|
| 活动栏/侧边栏入口 | 「源代码管理」入口出现，角标显示待提交数 |
| 列表排版 | `M CommentController.java` + 弱化目录 `backend/src/main/java/com/blog/controller` |
| 默认全选 | 3 个改动默认勾选，按钮显示「提交 3 个文件」 |
| 提交 | 图表 6 → 7，工作区清空，角标消失 |
| 图表展开 | 文件清单含增删行数，单文件 diff 正常 |
| 回滚 | 图表 7 → 8，新增 `Revert "..."`，旧提交全部保留 |
| 审查页 | 只剩 `ChangesPanel` 与同步面板，无 git tab |

**实测修掉的缺陷**：`selected` 从 `Set` 改为数组后模板仍调用 `.has()`，整块列表渲染崩溃
（`TypeError: $setup.selected.has is not a function`）。改用 `selectedSet` 计算属性后恢复。

### 11.3 首次提交前的读路径修复（2026-11-09）

**现象**：全新项目打开侧边栏的「代码」，弹出
`读取提交历史失败：fatal: your current branch 'master' does not have any commits yet`。

**原因**：`commits()` 用「HEAD 文件存在」判断仓库能不能读。仓库是 `status()` 建出来的，HEAD 必然
存在，但没有任何提交，`git log` 直接失败，错误被包成 `Result.error` 交给前端提示框。同一处
`workingDiff()` 对未出生的 HEAD 执行 `diff HEAD` 也会失败；未跟踪文件即使在有历史之后也只得到空 diff。

**修复**：`commits()` 改用 `hasCommits`；`workingDiff()` 在没有基线时改走 `--no-index` 的空设备比较，
并给 `GitCommandRunner` 加 `gitDiffNoIndex`（只额外接受退出码 1）。三处 diff 同时去掉 `--binary`，
二进制文件不再把 base64 补丁铺满面板。回归测试四个：
`commitHistoryIsEmptyBeforeTheFirstCommit`、`workingDiffShowsTheWholeFileBeforeTheFirstCommit`、
`workingDiffShowsUntrackedFileAsAddedAfterTheFirstCommit`、
`workingDiffShowsBinaryFileAsASingleNotice`。

**实测**

| 项 | 结果 |
|---|---|
| 项目 277（仓库 0 提交）历史 | `code=0`、空列表，不再是错误 |
| 项目 277 未跟踪文件 diff | 660 字符，`new file mode` + 全 `+` 行 |
| 项目 2（6 次提交）历史 | 不变：`a45ac81` 起 6 条 |
| 项目 2 改动文件 diff | 正常输出 `--- a/README.md` / `+++ b/README.md` |

### 11.4 diff 改到中栏展示（2026-11-09）

侧边栏就地展开 diff 的方案被替换：260px 宽看不清，展开后把文件列表挤走。改为复用
`CenterDiffViewer`，在中栏开只读 diff 标签（§6.5.1）。

**浏览器实测**（项目 277 无提交 + 项目 2 有 6 次提交，浅色/深色各一遍）

| 项 | 结果 |
|---|---|
| 点「更改」里的文件 | 中栏出现 `.labex-agentignore` 标签，标题 `+34`，34 行全为新增 |
| 侧边栏 | 不再出现就地 diff，列表保持完整 |
| 只读语义 | 「保留 / 回退 / 编辑源码」三颗按钮隐藏 |
| 面包屑 / AI 输入框 | 显示真实路径 `README.md`，没有 `::git` 后缀 |
| 点图表里的文件 | 中栏打开该提交的 diff，行号从 `119` 起，`-2` 行标红 |
| 再点同一文件 | 标签关闭，回到空白态 |
| 文件头行 | `new file mode` / `index 0000..111` 不再出现在正文行里 |
| 深色主题 | 增删行另一侧的行号格不再是白斑 |

**未做**：push、分支、行级暂存、自动提交、硬链接快照（§5.2）。

### 11.5 面板视觉与交互打磨（2026-11-09）

初版功能正确但"素"：图标是手写 SVG、提交按钮禁用时是一整块灰、文件行只有状态字母、
图表没有日期分组、clone 区块常驻占位。本轮按「克制精致版」重做视觉层，信息密度与布局不变。

| 项 | 改法 |
|---|---|
| 图标 | 12 个 `~icons/lucide/*` 按需引入（刷新/链接/对勾/加载/文件 diff/分支/提交/清单/箭头/复制/用户/回滚） |
| 提交按钮 | 禁用态描边幽灵、可提交态实心强调色 + hover 抬升；右侧按钮改为「全选/取消全选」 |
| 文件行 | 复用 `FileIcon` 文件类型图标 + 状态方块（带色底）+ hover 箭头；状态方块 `margin-left:auto` 贴右 |
| 图表 | 按天分组 + 时分 + HEAD 光晕 + 展开动效 + 作者图标 |
| 空状态 | 图标 + 结论 + 说明 |
| clone | 默认折叠，展开才取令牌；复制按钮带「已复制」反馈 |
| 动效 | 120–160ms、统一缓动；`prefers-reduced-motion` 下全部降级为无位移 |
| 可访问性 | 分组头 `aria-expanded`、图标按钮 `aria-label`、`:focus-visible` 焦点环 |

**顺带修正**：活动栏入口文案「代码」→「源码」。260px 侧边栏下四个 tab 各 60px，
「源代码」被截断，「源码」与另三个保持等宽且不截断。

**浏览器实测**（项目 2，浅色/深色各一遍，临时文件验收后已清理）

| 项 | 结果 |
|---|---|
| 头部 | `master` chip 60px，标题不截断，刷新/克隆按钮 hover 有底色 |
| 提交按钮 | 空信息时描边灰字；输入信息 + 勾选后变实心强调色（`rgb(79,70,229)`） |
| 文件行 | `Widget.vue` / `helper.py` / `style.css` 各自的类型图标正确；目录 `src/components/demo` 从尾部截断 |
| 状态方块 | 15×15，底色 `rgba(58,157,93,0.16)`，深色下 `#73c991` |
| 打开箭头 | 默认 `opacity:0`，hover/active 时 1（150ms 过渡） |
| 图表分组 | 「今天」/「2026-11-07」/「2026-11-06」三段，行内显示 `01:17`、`18:36` |
| HEAD 节点 | 实心强调色 + 3px 光晕 |
| clone 展开 | 折叠时不请求令牌，展开后显示完整命令与「复制」 |
| 点文件 | 中栏打开只读 diff，侧边栏高亮当前行并显示箭头 |

**未做**：行内暂存/撤销、按目录折叠分组、提交搜索。侧边栏 260px 下再塞行内操作会把文件名挤没。

