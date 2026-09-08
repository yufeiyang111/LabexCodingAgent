# LabexAgent CI/CD 自动化流水线发布与数据库迁移规范手册 (Agent Playbook)

> **文档性质**：本文档为全仓库 AI Agent（包括人类工程师）执行生产发布、持续交付与数据库变更的**最高执行规范**。  
> **使用方式**：当接到“部署到线上”、“发布新版本”、“上线前后端与数据库变更”、“触发流水线”等指令时，**必须严格遵循本文档所定义的流程、门禁和操作指令执行，严禁跳步或采用未经允许的危险命令**。

---

## 一、 流水线整体架构与职责分工

当前生产环境部署在腾讯云 Debian 服务器（配置为 2C 2G 低内存实例）。为杜绝在生产服务器上现场编译 Java/Node 导致 OOM（内存溢出宕机），系统全面采用 **云端编译 + 镜像托管 + 增量幂等迁移 + 远端平滑热更** 的自动化流水线（配置定义于 `.github/workflows/deploy.yml`）。

```text
┌────────────────────────────────────────────────────────────────────────┐
│ 1. 开发者 / Agent 本地质量门禁 (Local Pre-Flight Checks)                │
│    - 后端: mvn test-compile                                            │
│    - 前端: npx vite build && check-chunk-budget && npm run test        │
│    - 数据库: 幂等增量脚本 upgrade-YYYYMMDD.sql                          │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ git push origin main / git push origin v*
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. GitHub Actions 云端构建矩阵 (Ubuntu-Latest, 7GB 内存隔离环境)         │
│    - 后端编译: JDK 17 Temurin -> mvn clean package -> JAR 产物          │
│    - 镜像发布: 打包 Docker 镜像 -> 推送至腾讯云 TCR (个人版内网加速)       │
│    - 前端打包: Node 20 -> npm ci -> npm run build -> 打包成 tar.gz     │
│    - 升级提取: 提取 deploy/linux/migrations/ 下最新增量 SQL 为 upgrade-db.sql │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ SCP 上传 (产物单文件秒传，避免小文件网络卡死)
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. 腾讯云生产服务器自动发布 (Debian, /srv/labex-agent/app)               │
│    [Step A] 增量数据库迁移: 自动在 MySQL 容器内执行 upgrade-db.sql (幂等执行)│
│    [Step B] 前端静态资源更新: 解压覆盖 frontend-dist/ 并清理临时包        │
│    [Step C] 后端容器平滑拉取: docker compose pull backend (从 TCR 秒级拉取)│
│    [Step D] 业务无损滚动重启: docker compose up -d --no-deps backend   │
│    [Step E] 资源回收自愈: docker image prune -f 释放虚悬镜像磁盘占用    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 二、 发布前强制质量门禁（Pre-Flight Checklist）

在执行 `git push` 或触发任何发布流程前，Agent **必须在本地逐项执行并通过以下三道质量门禁**：

### 1. 后端编译与语法检查
确保新增实体、Service、Controller 或 Mapper 无语法错误、无缺失依赖：
```bash
# 进入 backend 目录，使用项目内置 settings-local.xml
cd backend
mvn -s settings-local.xml test-compile -DskipTests
```
* **通过标准**：`BUILD SUCCESS`，退出码为 `0`。若涉及核心业务逻辑变更，应追加执行对应单元测试（如 `mvn -s settings-local.xml test -Dtest=MonitorUserControllerTest`）。

### 2. 前端单元测试全量验证
确保改动没有破坏既有组件契约与状态流：
```bash
# 进入 frontend 目录运行全量测试套件
cd frontend
npm run test
```
* **通过标准**：全部 330+ 项单元测试 100% 通过（PASS）。

### 3. 前端生产构建与分块体积预算检查
确保生产环境 Rollup/Vite 正常打包，且产物体积未突破阈值：
```bash
cd frontend
npx vite build && node scripts/check-chunk-budget.mjs
```
* **通过标准**：Vite 构建成功，且 `check-chunk-budget.mjs` 打印出的所有 JS 块大小均在预算安全线以内（如 `CloudWorkspace` < 1.5MB、主包 < 1.3MB）。

---

## 三、 数据库增量变更规范（Database Migration Rules）

**核心铁律**：生产环境严禁直接全量导入/覆盖 `schema.sql`，必须保持**“完全幂等（Idempotent）”**——即便脚本在生产数据库重复执行 10 次，也绝不会报错、绝不中断流水线、绝不破坏已有数据。

### 1. 增量脚本存放与命名
* 路径：`deploy/linux/migrations/upgrade-YYYYMMDD.sql`（如 `deploy/linux/migrations/upgrade-20260908.sql`）。
* 同步更新基线：修改了增量脚本后，必须同步更新开发环境基线文件 `backend/src/main/resources/sql/schema.sql`，确保新部署环境与旧升级环境结构一致。

### 2. 标准幂等模板代码

所有数据库增量改动必须放入 MySQL 临时存储过程，通过 `information_schema` 进行存在性检测：

```sql
-- ==============================================================================
-- LabexAgent 生产数据库增量安全迁移脚本 (示例)
-- 特性：完全幂等。重复执行绝不报错、不破坏已有数据、自动检测字段/索引是否存在。
-- ==============================================================================

DROP PROCEDURE IF EXISTS upgrade_labex_schema_temp;
DELIMITER //

CREATE PROCEDURE upgrade_labex_schema_temp()
BEGIN
    -- 1. 新增字段示例 (例如在 t_agent_task 表新增 parent_task_id)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_task' AND column_name = 'parent_task_id'
    ) THEN
        ALTER TABLE t_agent_task ADD COLUMN parent_task_id BIGINT DEFAULT NULL;
    END IF;

    -- 2. 新增复合索引示例 (例如在 t_access_log 表增补复合查询索引)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics 
        WHERE table_schema = DATABASE() AND table_name = 't_access_log' AND index_name = 'idx_access_log_uid_time'
    ) THEN
        ALTER TABLE t_access_log ADD INDEX idx_access_log_uid_time (user_id, request_time);
    END IF;

    -- 3. 新建独立数据表示例 (必须使用 CREATE TABLE IF NOT EXISTS)
    CREATE TABLE IF NOT EXISTS t_example_record (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        create_time DATETIME DEFAULT CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

END //
DELIMITER ;

-- 执行并清理临时存储过程
CALL upgrade_labex_schema_temp();
DROP PROCEDURE IF EXISTS upgrade_labex_schema_temp;
```

---

## 四、 Git 提交流程与流水线触发

### 1. 代码暂存与规范提交
提交信息必须遵循语义化提交（Conventional Commits）：
```bash
# 检查工作区变动
git status

# 暂存涉及修改的代码文件与增量 SQL
git add backend/ frontend/ deploy/linux/migrations/ deploy/linux/

# 语义化提交示例
git commit -m "feat(ops): 增加用户分析与行为监控功能模块及索引迁移"
```

### 2. 推送至远程主分支
```bash
git push origin main
```
* **网络超时防御**：若推送 GitHub 时遇到 `Recv failure: Connection was reset` 或代理超时，可显式指定本地 Clash/代理端口重试：
  ```bash
  git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin main
  ```

### 3. 触发自动化发布流水线（二选一）

#### 方式 A：推送版本 Tag 触发自动构建（推荐规范发布）
流水线监听所有符合 `v*` 规则的 Tag（如 `v1.0.0`、`v1.0.1`）：
```bash
# 1. 本地打带注记的版本 Tag
git tag -a v1.0.0 -m "release: v1.0.0 用户监控与运维大盘版本发布"

# 2. 推送 Tag 至 GitHub
git push origin v1.0.0
```
推送成功后，GitHub Actions 会立刻被自动触发并拉起完整构建与发布流程。

#### 方式 B：GitHub Actions 页面/CLI 手动触发（灵活重试）
若不想生成新 Tag，或者希望手动验证某次提交：
1. **浏览器界面操作**：
   - 访问 GitHub 仓库的 **Actions** 标签页；
   - 在左侧选中 **Tencent Cloud CI/CD Pipeline**；
   - 点击右侧 **Run workflow** 下拉菜单，选择 `main` 分支，点击绿色按钮触发。
2. **通过 GitHub CLI 触发**（如已配置）：
   ```bash
   gh workflow run deploy.yml --ref main
   ```

---

## 五、 部署后自愈与线上验收 SOP

流水线执行完毕后，Agent 应通过远程探针或指导用户检查以下 4 个关键指标，确认发版成功：

| 验证项 | 验证手段 / 探针接口 | 正常预期指标 |
|:---|:---|:---|
| **1. 页面可访问性** | `curl -I https://labexagent.123845.xyz` | HTTP 状态码为 `200 OK`，证书有效。 |
| **2. 后端核心存活** | `curl -s https://labexagent.123845.xyz/api/ops/summary` | 返回结构包含 `code` 字段，服务未宕机。 |
| **3. 数据库迁移结果** | 进入 MySQL 容器或观察控制台日志 | `upgrade-db.sql` 成功执行无语法报错，索引与字段已注入。 |
| **4. 容器与磁盘水位** | 服务器命令 `docker compose ps` | 容器状态均为 `Up (healthy)`，`docker image prune` 已清理悬空镜像。 |

---

## 六、 故障回滚标准操作程序 (Rollback SOP)

若新版本部署后出现不可恢复的致命异常（如启动 Crash、核心流程阻断），执行以下紧急回滚步骤：

1. **镜像快速秒级回滚**：
   - SSH 登录腾讯云服务器 `/srv/labex-agent/app`；
   - 修改 `docker-compose.yml` 中的 `image` 标签，临时指向上一稳定版本（如 `:v0.9.9` 或特定 Git Commit Hash）；
   - 执行 `docker compose --env-file .env.production up -d --no-deps backend` 恢复服务。
2. **前端快速回滚**：
   - 服务器上保留有历史版本的静态文件备份，重新软链或解压历史稳定 `frontend-dist.tar.gz` 即可秒级完成前端恢复。
