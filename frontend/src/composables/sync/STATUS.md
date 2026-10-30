# 同步核心（I0–I2）交付状态：接线前必修清单

> **为什么这个文件在 `src/` 而不是 `docs/`**：`docs/` 整个目录被 `.gitignore` 忽略（第 74 行，
> "entire directory is private and never pushed"），换机器 / 新克隆 / CI 都读不到。
> 这份清单是**代码级的阻塞条件**，必须跟代码一起走。
> 服务端同步供给契约的权威说明在 `backend/src/main/java/com/labex/labexagent/sync/README.md`。
>
> **状态（2026-10-06 第二轮）**：服务端同步供给已修正 P0 并做过真实 MySQL + JVM 验证；
> **plan 层已能吃 feed 形态**（C01/C03/C04 已修，含失败回归测试）；**仍未接 UI、仍未接真实目录**。
> 在 ❌ 项修完之前，禁止把本目录接到真实目录、禁止接 UI。

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

## 3. 明确延期（本次不做，不要顺手扩范围）

1. **接线到真实 API**：`agentChangeFeed` + `loadAllAgentChangeFeed` 已实现分页循环、两条游标原样回传、`changeId+status` 去重、游标不前进/页数上限保护；orchestrator 已把 `nextCursor` 持久化到 journal。仍未接 UI 和真实目录授权。
   `hasMoreChangeSets=false && hasMoreChangedFiles=false`）——plan 层已就绪，但**尚未接 UI**。
2. **同秒同路径的集合内顺序**：`create_time` 秒级 + UUID 主键 ⇒ 服务端集合内顺序不可靠。
   注意：**新增 `seq` 列并不能消除"延迟提交漏行"**——已实测 `AUTO_INCREMENT` 的分配顺序 ≠ 提交顺序
   （未提交事务先拿到 seq=1，后提交的 seq=2 先可见，读方把游标推到 2 后 seq=1 的行永久不可见）。
   真正消除残留需要"提交序可见"的机制（如库内 outbox/事件日志）或继续用重叠窗口。
3. **前端 UI 组件与浏览器授权流程**（I3-c/I3-d）。
4. **L3 / daemon / 规则集安全**：不在本轮范围内。

## 4. 门禁（改动本目录时必须跑）

```bash
node --test frontend/src/composables/sync/*.test.mjs   # 本目录单测（71 用例）
npm run test                                           # 前端全量（498 用例）
npm run check:undef                                    # acorn no-undef + 欠账棘轮（白名单外的解析失败会 exit 1）
node ../scripts/check-file-lines.mjs                   # 单文件 ≤400 行（相对仓库根解析，任意 cwd 都可执行）
```

另有一个**非开发者可用的验收脚本**（启动后端后一条命令看结果）：
`node scripts/verify-sync-feed.mjs --project 2 --after 339 --tail 2026-07-10T00:00:00 --expect <数据库条数>`
——它把「分页调成每页 1 条后一路翻到底」并核对条数，用于证明"不漏不重、能收敛"。

新增 `frontend/src/components/sync/` 时，必须把该目录加进 `scripts/check-file-lines.mjs` 的
`DEFAULT_TARGETS`（脚本对"目录不存在 / 扫不到源码"会 exit 1，这是有意为之）。
