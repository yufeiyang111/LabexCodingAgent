# 同步核心（I0–I2 + I3）交付状态

> **为什么这个文件在 `src/` 而不是 `docs/`**：`docs/` 整个目录被 `.gitignore` 忽略（第 74 行，
> "entire directory is private and never pushed"），换机器 / 新克隆 / CI 都读不到。
> 这份清单是**代码级的阻塞条件**，必须跟代码一起走。
> 服务端同步供给契约的权威说明在 `backend/src/main/java/com/labex/labexagent/sync/README.md`。
>
> **状态（2026-11-06，I3 已完成并通过 live 验证）**：服务端同步供给已修正 P0 并做过真实 MySQL + JVM 验证；
> plan 层已吃 feed 形态（C01/C03/C04 已修）；**I3-a/b/c/d 已完成**：journal 持久化、
> Vue 接线、5 个 UI 组件、接入 Review 页。
> **已在真实 Chromium + 真实后端上完成 live 验证**（见 §4.0），过程中发现并修复了两个真实缺陷
> （冲突按路径去重、中止后允许回退）。仍未验证的只剩原生目录选择弹窗的人工交互。

## 1. 接线前必须修（每条先写一个失败回归测试，再改实现）

| ID | 现象（已在真实目录/真实数据库上复现） | 位置 | 状态 |
|---|---|---|---|
| C01 | 后端真实新增类型是 `create`，计划器只特判 `add` → 新增文件被误判成 `missing-local` 冲突；`normalizeChange` 丢弃 `status`，未批准/被拒的变更仍会写盘 | `changeSetSyncPlan.js` | ✅ 已修：识别 `create`/`beforeExisted`；`status ∈ {rejected,undone,failed}` 不再写盘并给出显式 reason；截断内容不得当基线/目标 |
| C03 | 同一 change-set 内第一个文件成功就把游标推进到整个 `changeSetId`；第二个失败后重试按 `<= cursor` 跳过它 → **永久漏同步** | `changeSetSyncPlan.js`（规划侧）、`changeSetSyncOrchestrator.js`（应用侧） | ✅ 已修：规划侧只认"从前往后连续完整"的集合（`completedChangeSetIds`/`changeSetProgress`/`nextCursor`）；应用侧游标只停到"步骤全部成功"的集合 |
| C04 | 全程用同一份初始 probe，不模拟前一步结果；后端列表按 `update_time DESC` → 同路径连续修改被误判冲突，倒序输入下最终写成中间版本 | `changeSetSyncPlan.js` | ✅ 已修：虚拟本地视图（每步结果写回），集合按 id 升序处理，同路径链式修改串成多步且 `previousContent` 逐级推进 |
| C05 | 探针读取失败只记 `failures`，缺失探针等同"文件不存在" → 已有内容被覆盖且 journal 错记 `previousExisted:false` | `syncProbe.js`、`changeSetSyncPlan.js` | ✅ 已修：probe 三态化为 `present/absent/unknown`；`unknown` 在 strict/skip/overwrite 下都硬阻断，零写入；回归测试见 `syncProbe.test.mjs`、`changeSetSyncPlan.test.mjs`、`changeSetSyncOrchestrator.test.mjs` |
| C06 | 先改文件、后存 journal；**默认 store 是"空 journal + 无操作 save"** | `changeSetSyncOrchestrator.js:16-17,70-85` | ✅ 已修：缺少 `load/save` 时在构造期抛错（任何文件写入之前），并补两个回归测试 |
| C07 | 回退不校验当前文件是否仍是本次同步产物 → 覆盖用户后续手工编辑；逆操作全做完才裁剪 journal | `syncJournal.js`、`changeSetSyncOrchestrator.js` | ✅ 已修：journal 记录 `appliedContent/appliedExisted`；回滚先整体预检所有目标，发现用户编辑、重建删除文件或旧 journal 时零写入失败，成功后才裁剪 journal；回归测试见 `syncJournal.test.mjs`、`changeSetSyncOrchestratorRollback.test.mjs` |
| C08 | `failed→rolling-back`、`idle→rolling-back`、`blocked→scanning` 都是非法迁移；扫描阶段异常不在 try 内 | `syncSessionState.js`、`changeSetSyncOrchestrator.js` | ✅ 已修：允许 `blocked/done → scanning` 重试；扫描/加载/探针/规划异常统一落 `failed`；回归测试见 `syncSessionState.test.mjs`、`changeSetSyncOrchestrator.test.mjs` |
| C09 | run/rollback 无互斥，同状态迁移被当作无操作 → 并发两次 `run()` 都成功、同一文件被写两次 | `changeSetSyncOrchestrator.js` | ✅ 已修：run/rollback 共用实例级单飞锁，并发调用显式拒绝；锁在 `finally` 释放；回归测试覆盖并发 run、并发 rollback、run/rollback 交叉与异常后重试 |
| C10 | `resolve(result?.result ?? result)` 在 IndexedDB 缺键时返回 `IDBRequest` 而不是 null | `directoryHandleStore.js` | ✅ 已修：request-like 结果等待 `onsuccess` 并返回 `result ?? null`；fake IndexedDB 覆盖缺键、命中和 request 失败；真实浏览器路径仍需 live smoke |
| C11 | 本地读写只有 UTF-8 文本，云端快照在 120000 字符处静默截断且 DTO 无完整性标志 | `changeSetSyncPlan.js` | ✅ 已修：plan 层已消费 `beforeTruncated`/`afterTruncated`，截断基线、截断目标和截断删除均阻断；回归测试见 `changeSetSyncPlan.test.mjs`。真实浏览器二进制读写仍不在本模块范围 |
| C12 | 无单文件/批次字节、文件数、journal 字节上限；每步复制整个 entries | `syncLimits.js`、`syncProbe.js`、`changeSetSyncOrchestrator.js` | ✅ 已修：集中上限覆盖单文件、批次文件数、批次字节、journal 条目与字节；超限在任何 journal 保存前失败；回归测试见 `syncProbe.test.mjs`、`changeSetSyncOrchestrator.test.mjs` |
| C13 | 排除规则只查根级 `.labex/`、大小写敏感、漏 `.labex-rename-*`；glob 语义与服务端不同 | `syncExclusion.js` | ✅ 已修：保护区按任意层级且大小写不敏感；对齐 `*`/`**`/`?`、目录规则与 `!` 忽略语义；回归测试见 `syncExclusion.test.mjs` |

## 2.5 前端 plan 层的输入契约（2026-10-06 已实现）

`buildSyncPlan` 现在同时接受两种输入，归一化后走同一套逻辑：

```js
// feed 形态（推荐，直接来自 /changes/feed）
{ changeSets: [{ changeSetId, taskId, status, updateTime, files: [FileEntry] }], cursor, probe, policy, ignorePatterns }
// 旧扁平形态（仍然支持，按 changeSetId 分组）
{ changes: [ ... ], cursor, probe, policy, ignorePatterns }
```

返回新增：`completedChangeSetIds`（从前往后连续完整处理的集合）、`changeSetProgress`（每个集合的
planned/accounted/conflicts/complete）。**调用方必须用 `nextCursor` 推进游标**，不要自己算 `max(changeSetId)`。

`loadChanges` 可以直接返回 feed 形态对象；orchestrator 会把它原样交给 plan（无需适配层）。

## 2. 服务端已完成（含本轮 P0 修复）

```
GET /student/projects/{projectId}/agent/changes/feed?afterChangeSetId=&tailUpdateTime=&tailChangeId=&limit=
```

- **两条独立进度线**：`afterChangeSetId`（新集合）与 `(tailUpdateTime, tailChangeId)`（游标之内变化的文件）。
  合成一个游标会导致互相吞没——这就是本轮修的 P0：137 条尾巴文件只交付 1 条，按水位线续拉后返回 0 条。
- **截断时续拉点 = 最后一条已交付行的键**（不是请求时间）；完整时回退 `tail-overlap-seconds` 秒重扫。
- 游标时间截断到秒（列为秒级 DATETIME，MySQL 写入四舍五入）；排序键是 `(update_time, change_id)` 复合键。
- 扫走 `/changes`（80 条展示列表）的旧契约保持不变。

**验证证据（本机真实环境）**：
- 真实 MySQL 隔离库：精度（0.9876→进位到下一秒）、同秒多文件、边界相等、延迟提交、越权隔离 —— 7/7 通过。
- 真实 JVM：`limit=1` 逐页续拉，137 页交付 137 个文件，**漏交付 0 / 多交付 0**；旧 `/changes` 仍返回 80 条；
  用 student 1 的 token 访问他人的 project 287 返回空；`undone` 行能通过尾巴被下发。

## 3. I3 交付内容（2026-11-05）

| 任务 | 产出 | 说明 |
|---|---|---|
| I3-a | `journalStore.js` | IndexedDB Repository，键 = projectId；`save` 失败显式抛错、`load` 缺键返回 null（不静默降级） |
| I3-b | `useChangeSetSync.js` | Vue 接线：feed 分页 → orchestrator → 响应式 session；切换项目丢弃旧目录授权 |
| I3-c | `components/sync/` 4 个组件 | `SyncToLocalButton` / `SyncPanel` / `SyncProgressList` / `SyncConflictDialog`，全部只用 `var(--theme-*)` |
| I3-d | `views/CloudWorkspace.vue` Review 页 | 左改动面板 + 右同步面板（`.review-split`，<1180px 堆叠） |
| 附带 | `constants/sync.js` | 冲突策略、状态文案、目录选择器选项集中管理 |

**本轮修掉的两个真实缺口**（都先写了失败回归测试）：

1. **尾巴行（`changedFiles`）此前完全未被消费**。服务端明确说明「已同步集合被追加文件」与
   「文件被 undo/apply/reject 改状态」**不会**产生新 change-set，只靠 `changeSets` 会永久漏掉。
   现在 plan 层把尾巴行纳入计划，但**不推进游标**（游标只由完整集合决定）。见 `changeSetSyncTail.test.mjs`。
2. **重复交付的尾巴行会让 strict 策略永久卡死**。重叠窗口会重发同一行，此时本地内容已等于
   `afterContent`，旧逻辑只比 `beforeContent` 判成 `content-drift` → 每次同步都阻塞。
   现在先比目标态再比基线，命中目标态即 `already-applied` 跳过。

## 4. 已声明未验证（不得当成已解决）

- ~~**浏览器 live 路径**~~：**已于 2026-11-06 在真实 Chromium 验证**，见 §4.0。
- ~~**真实后端联调**~~：**已对真实 `/agent/changes/feed` 跑过端到端**，见 §4.0。
- **二进制文件**：同步仍是 UTF-8 文本读写，二进制文件不在本模块范围。
- **目录授权弹窗**：`showDirectoryPicker` 是原生对话框，无法用 CDP 自动化。
  live 验证时只替换了该入口（返回一个真实的 OPFS `FileSystemDirectoryHandle`），
  其后的 `attachHandle → adapterFactory → orchestrator → 写盘` 全部走生产代码。
  弹窗本身的交互仍需人工点一次确认。

## 4.0 I3 live 验证记录（2026-11-06，真实 Chromium + 真实后端 8081）

后端 `start_backend.ps1 -Port 8081` 启动（PID 20260，Spring 23.1s 就绪），
项目 288（45 条变更行 / 22 个路径 / 486KB）。

**后端契约**

| 检查 | 结果 |
|---|---|
| `changeSets` 升序、文件清单完整 | 5 个集合（369,370,371,372,379），45 个文件，与数据库一致 |
| `nextCursor` 时区 | `2026-11-06T09:58:28`，与本机时间同区（Asia/Shanghai） |
| `limit=1` 逐页翻到底 | 5 页，45 个去重文件，最终 `afterChangeSetId=379`，不漏不重 |
| 尾巴线（`tailUpdateTime=2026-08-01`） | 45 条 `changedFiles`，`hasMoreChangedFiles=false` |

**浏览器同步**

| 场景 | 结果 |
|---|---|
| 首次同步（空目录） | 22 个文件写入，0 冲突 0 跳过 |
| 内容正确性 | 22/22 逐字节等于 feed 的最终 `afterContent` |
| 严格策略 + 脏目录 | `blocked`，冲突 1 个，本地文件**未被覆盖** |
| 跳过策略 | 21 写入 / 1 冲突跳过，本地文件保持原样 |
| 覆盖策略 | 22 写入，冲突文件被云端内容覆盖 |
| 回退（全部新建） | 22 个文件全部删除，目录清空 |
| 刷新后目录恢复 | 从 IndexedDB 恢复句柄，面板显示原目录名 |
| 已是最新 | 第二次同步返回「本地目录已是最新」，并给出重置入口 |
| 重置进度 | journal 清空、游标归零、目录绑定保留 |
| 中止 | 写到第 7 个文件停止，剩余 15 个不写 |
| 中止后回退 | 已写 7 个全部撤销 |
| 中途刷新 | journal 保留 22 条记录、游标 379，磁盘 22 个文件一致 |

**本轮 live 验证发现并修复的两个真实缺陷**（都先写了失败回归测试）：

1. **同一路径的冲突被重复记录**。`package.json` 在服务端有 3 行变更，
   冲突对话框显示「有 3 个文件与本地不一致」，实际只有 1 个。
   冲突是路径级事实，改为按 `path` 去重（`changeSetSyncPlan.js`）。
2. **中止后无法回退**。状态机不允许 `failed → rolling-back`，
   点「回退本次同步」直接报 `illegal sync state transition`，
   已写入的 7 个文件无法撤销。改为允许该迁移（`syncSessionState.js`）——
   是否有东西可回退由 journal 决定，`buildRollbackSteps` 为空时直接回 idle。

## 4.1 性能基线（2026-11-05，真实 Chromium + OPFS）

用 OPFS 目录句柄跑真实适配器与真实 IndexedDB，feed 用 project 288 的真实形状合成
（45 条变更行 / 22 个路径 / 486KB 内容）。**不是**生产目录的实测值，是同一套代码的等量负载。

| 阶段 | 每文件 | 22 个文件 |
|---|---|---|
| 探针读取 | 7.5ms | 165ms |
| 写盘（`createWritable`+`write`+`close`） | 28.5ms | 626ms |
| journal 落盘（3 次提交，共 646KB） | — | 10ms |
| 合计 | — | **833ms** |

单次写盘的固定开销拆解：`createWritable` 6.2ms + `write` 4.2ms + `close` 11.6ms ≈ 22ms，
**与内容大小基本无关**——20 个 100B 文件 515ms、20 个 200KB 文件 974ms。所以耗时由
**文件数**决定，不是字节数：1MB 分 22 个文件约 0.8s，同样 1MB 分 1000 个文件约 30s。

已修的两处真实浪费（都有回归测试）：

1. 探针从 `fileExists` + `readTextFile` 两次解析降为一次读取（`syncProbe.js`）。
2. 目录句柄缓存：`getDirectoryHandle` 每次约 1.1ms，40 次重复解析 118ms → 6ms
   （`fsAccessDirectoryAdapter.js`）。

**仍存在的规模问题**：journal 每次提交都把整个 journal 重新序列化并整条 `put`。
实测 50/200/500 个 4KB 文件的 journal 写入总量是 1.8MB / 23.6MB / 135.6MB，
而增量写入只需 0.4MB / 1.7MB / 4.2MB（差 4.4× / 14× / 32.5×）。
500 个文件时单是 journal 就 1.6s。改成按条目增量写是下一步的优化点，未做。

## 4.2 规模上限（2026-11-06，回答"能不能撑 10 万文件"）

**不能。而且第一个撞到的不是写盘速度，是内存与 journal 上限。**

| 约束 | 值 | 折算成文件数 |
|---|---|---|
| `MAX_BATCH_FILES` | 500 | 单批 500 个文件，超出**整批 blocked，零写入** |
| `MAX_JOURNAL_BYTES` | 32MB | 每条 entry 存 `previousContent` + `appliedContent`（两份），4KB 文件约 **3950 个**，20KB 文件约 **813 个** |
| `MAX_JOURNAL_ENTRIES` | 10000 | 1KB 文件约 10000 个 |
| journal 全量重写 | 每 8 步一次 | 10 万文件累计写 **2.4TB**（O(n²)） |

实测单文件写盘成本约 20ms（浏览器 File System Access，与内容大小无关）：

| 文件数 | 纯写盘耗时 |
|---|---|
| 500 | 约 10 秒 |
| 5000 | 约 100 秒 |
| 100000 | 约 33 分钟 |

**并行写无效**：浏览器侧 200 个文件，并发 1 / 4 / 16 分别是 4186 / 3961 / 3975ms，
几乎无差别（File System Access 的 `createWritable` 在渲染进程串行化）。
Node 侧并发有效（1000 个文件：串行 8.8s → 并发 4 为 4.0s），说明瓶颈在浏览器 API 而非磁盘。

## 4.3 与 git 的对比（同一台机器，NVMe SSD，2000 个 2KB 文件）

| 方式 | 耗时 |
|---|---|
| 直接逐文件写 | 21.2s |
| `git clone`（本地仓库） | 23.3s |
| `tar` 解包 | 15.4s |
| `Expand-Archive`（PowerShell zip） | 102.2s |

**git 没有更快**。它快的场景是"只传增量 + 稀疏检出"，而落到工作区仍要逐个写文件——
2000 个文件就是 20 秒量级。zip 反而最慢（PowerShell 实现问题，不代表 zip 格式）。

结论：**没有任何传输方式能绕过"每个文件一次落盘"的物理成本**。能优化的是
「不重复写已经一致的文件」和「一次写更少的文件」，而不是换协议。

## 4.4 真正可行的规模化方向（未实现，按收益排序）

1. **只写变更 + 内容寻址跳过**：本地已有相同内容的文件直接跳过，不发、不写。
   同步的瓶颈从"项目规模"降为"本次改动规模"——这也是 git 唯一真正的优势来源。
2. **journal 增量存储**：按条目追加 + 独立游标记录，消除 O(n²)。
   500 个文件时 journal 从 1.6s 降到约 0.18s，且解除 3950 个文件的硬上限。
3. **daemon（L3）**：常驻进程直接写磁盘，绕开浏览器 File System Access 的串行化。
   实测 Node 并发 4 可达 3.95ms/文件，比浏览器的 20ms 快 5 倍，且能在关页面后继续。
4. **大项目默认走导出 zip**：已有 `GET /{projectId}/export`，服务端打包、一次下载。
   对"首次拉取 10 万文件"这种场景，单流下载比逐文件写更合适。

## 5. 明确延期（本次不做，不要顺手扩范围）

1. **同秒同路径的集合内顺序**：`create_time` 秒级 + UUID 主键 ⇒ 服务端集合内顺序不可靠。
   注意：**新增 `seq` 列并不能消除"延迟提交漏行"**——已实测 `AUTO_INCREMENT` 的分配顺序 ≠ 提交顺序
   （未提交事务先拿到 seq=1，后提交的 seq=2 先可见，读方把游标推到 2 后 seq=1 的行永久不可见）。
   真正消除残留需要"提交序可见"的机制（如库内 outbox/事件日志）或继续用重叠窗口。
2. **L2 自动同步**：目前只有手动触发，未接入 apply 后自动入队。
3. **L3 / daemon / 规则集安全**：不在本轮范围内。

## 6. 门禁（改动本目录时必须跑）

```bash
node --test frontend/src/composables/sync/*.test.mjs   # 本目录单测（230 用例）
npm run test                                           # 前端全量（657 用例）
npm run check:undef                                    # acorn no-undef + 欠账棘轮（白名单外的解析失败会 exit 1）
node ../scripts/check-file-lines.mjs                   # 单文件 ≤400 行（相对仓库根解析，任意 cwd 都可执行）
npx vite build && node scripts/check-chunk-budget.mjs   # 构建 + chunk 预算
```

另有一个**非开发者可用的验收脚本**（启动后端后一条命令看结果）：
`node scripts/verify-sync-feed.mjs --project 2 --after 339 --tail 2026-07-10T00:00:00 --expect <数据库条数>`
——它把「分页调成每页 1 条后一路翻到底」并核对条数，用于证明"不漏不重、能收敛"。

`frontend/src/composables/sync` 与 `frontend/src/components/sync` 均已登记进
`scripts/check-file-lines.mjs` 的 `DEFAULT_TARGETS`（脚本对"目录不存在 / 扫不到源码"会 exit 1，这是有意为之）。
