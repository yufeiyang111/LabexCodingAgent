# B1 同步供给（只读投影）契约

> **为什么这份说明在代码目录里**：`docs/` 整个目录被 `.gitignore` 忽略（第 74 行），
> 换机器 / 新克隆 / CI 都读不到。凡是"调用方必须照做"的契约必须跟代码一起走。
> 背景与评审证据见 `docs/2026-09-28-云本混合-同步核心对抗性评审报告.md`（本机可见）。

## 1. 端点

```
GET /student/projects/{projectId}/agent/changes/feed
      ?afterChangeSetId=<long>         新集合进度（已完整落地的最后一个 change-set）
      &tailUpdateTime=<ISO-8601>       尾巴扫描进度（本地时间，秒精度）
      &tailChangeId=<string>           尾巴扫描 tie-breaker（同一秒多文件时必须带）
      &limit=<int>                     change-set 条数，夹取到 labex-agent.sync-feed.max-change-sets-per-page
```

`revisionTime` 是**已弃用的别名**（等价于 `tailUpdateTime`）。当前没有生产调用方
（前端尚未接线），下线条件：仓库内不再出现该参数引用。

与既有 `GET .../agent/changes` 的分工：后者是「最近 80 条 + `update_time DESC`」的**展示用列表**
（改动面板在用，契约保持不变），无游标、不可续拉，**不能**作为本地落地的数据源。

## 2. 响应

```json
{
  "changeSets":       [ { "changeSetId": 340, "taskId": 12, "status": "pending", "updateTime": "...",
                          "files": [ /* FileEntry */ ] } ],
  "changedFiles":     [ /* FileEntry */ ],
  "nextCursor":       { "afterChangeSetId": 340, "tailUpdateTime": "2026-10-06T16:08:01", "tailChangeId": "" },
  "hasMore":          false,
  "hasMoreChangeSets":       false,
  "hasMoreChangedFiles":     false
}
```

`FileEntry`：`changeId` / `changeSetId` / `relativePath` / `changeType`(`create|modify|delete`) / `status` /
`beforeExisted` / `afterExisted` / `beforeTruncated` / `afterTruncated` / `beforeContent` / `afterContent` / `updateTime`。

- **存在性由 `changeType` 推导**，不要从"内容是否为空串"猜（新增文件的 `beforeContent` 也是空串）。
- **`*Truncated=true` 的内容不能作为回退依据**：`GitSnapshotService.limit()` 在 120000 字符处截断并追加标记。

## 3. 两条进度线（必须分别保存，不能合成一个游标）

| 进度 | 字段 | 决定什么 |
|---|---|---|
| 新集合 | `afterChangeSetId` | 哪些 change-set 需要下发（`change_set_id > after`，升序，每个集合带完整文件清单） |
| 尾巴扫描 | `tailUpdateTime` + `tailChangeId` | 旧集合里哪些文件的新状态要补发 |

**为什么必须分开**：`change_set_id <= after` 的文件发生变化时（追加文件、`undo`、`apply`、`reject`）
**不会**产生新 change-set，只会改 `update_time`/`status`；只看 `changeSets` 会永久漏掉这些行。
而如果两条进度共用一个游标，"推进新集合"就会吃掉还没扫完的尾巴页——
这正是已修复的 P0 缺陷：137 条尾巴文件只交付 1 条，按水位线续拉后返回 0 条、剩余 136 条永久不可见。

## 4. 续拉规则

1. 请求时把 `nextCursor` 的三个字段**原样**回传（`afterChangeSetId`、`tailUpdateTime`、`tailChangeId`）。
2. `hasMore = true`（或 `hasMoreChangedFiles = true`）时必须继续请求，直到两者都为 false 才能认为追平云端。
3. **尾巴页被截断时，`nextCursor` 是"最后一条已交付行的键"**（该行的 `updateTime` + `changeId`），
   下一页正好从它之后继续；**不要**把它替换成本次请求的时间，也不要自行加一减一。
4. **幂等去重**：同一秒内可能有多个文件、且写入会四舍五入到秒，所以扫完一页后服务端会把
   `tailUpdateTime` 回退 `tail-overlap-seconds` 秒重扫。同一条 `(changeId, status, updateTime)`
   可能被交付多次——客户端按 `changeId` + `status` 幂等处理，**不要**假设"每条只来一次"。
5. 首次同步（不传 `tailUpdateTime`）不返回 `changedFiles`，只给出一个基线游标。
6. 客户端只在**持久化处理结果之后**才推进 `nextCursor`；否则两次请求之间的变化会丢。

## 5. 精度与时区（易踩坑）

- `t_agent_file_change.update_time` 是**秒级** `DATETIME`；MySQL 对超精度写入**四舍五入**
  （隔离库实测：写入 `10:00:00.987654321` 读回 `10:00:01`）。
- 游标时间进 SQL 前一律**截断到秒**：带纳秒的游标比库中任何行都"新"，会直接吃掉待交付的行。
- 尾巴扫描的排序键是 `(update_time, change_id)` 复合键——同一秒多文件只比时间会整批跳过。
- 水位线用 `Clock.systemDefaultZone()`：必须与写行时的 `LocalDateTime.now()` 同时区；
  用 UTC 会让水位线整体提前一个时区偏移量（已修复）。
- 扫描上界取"请求时刻 − 重叠窗口"，使请求期间写入的行留到下一轮，而不是依赖 JVM 与 DB 时钟完全同步。

## 6. 支撑索引

| 表 | 索引 | 用途 |
|---|---|---|
| `t_agent_change_set` | `idx_cs_project_set (student_id, project_id, change_set_id)` | 游标分页按 `change_set_id` 升序 |
| `t_agent_file_change` | `idx_fc_project_update (student_id, project_id, update_time)` | 尾巴扫描按 `update_time` 区间 |

旧库由 `AdditiveSchemaMigrator` 在启动期幂等补齐；新库见 `sql/schema.sql`。
真实 EXPLAIN（本机 926 行 file_change / 389 change_set）：两条查询都走上述索引（`range`，无 filesort）。
迁移的"补齐 + 重复执行"由 `AdditiveSchemaMigratorMySqlIsolationTest` 在隔离库上验证
（记录实际 DDL：第一次执行两条 `ADD INDEX`，第二次零相关 DDL）。

## 7. 已知残留（未修复，不要当成已解决）

- `t_agent_file_change.create_time` 秒级 + 主键 UUID ⇒ **同一秒内对同一路径的多次修改，集合内顺序不可靠**。
- **重叠窗口是必要的，不是权宜之计**：已实测 `AUTO_INCREMENT` 的分配顺序 ≠ 提交顺序
  （未提交事务先分配 seq=1，后提交的 seq=2 先可见；读方把 seq 游标推到 2 后，seq=1 的行
  **永久不可见**）。因此"加一个 seq 列"**不能**消除本条残留，只修集合内"创建顺序"。
  要真正消除延迟提交漏行，需要"提交序可见"的机制（库内 outbox / 事件日志）或保留重叠窗口。
- 前端已具备消费本端点的能力（`changeSetSyncPlan.js` 直接吃 `changeSets[].files[]`，见
  `frontend/src/composables/sync/STATUS.md`），但**尚未接线到真实 API/UI**——属于明确延期项。
- `changedFiles` 的行数上限是每页配置值；客户端必须循环到 `hasMoreChangedFiles=false`（不要只看 `hasMore`）。

## 8. 验证入口

```bash
# 单元测试（无需数据库）
-Dtest=AgentSyncFeedServiceTest
# 真实 MySQL：隔离库上的精度 / 分页 / 并发提交（需要 LABEX_AGENT_DB_* 环境变量，否则自动跳过）
-Dtest=AgentSyncFeedServiceMySqlIsolationTest
# 真实 MySQL：缺失索引补齐 + 重复迁移幂等（隔离库，自动跳过同上）
-Dtest=AdditiveSchemaMigratorMySqlIsolationTest
```
