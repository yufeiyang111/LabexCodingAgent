# LabexAgent 验收脚本

这些脚本只用于本地、显式启用的验收环境。`agent-runtime.ps1` 会：

1. 拒绝接管已被占用的端口；
2. 在 `acceptance` profile 启动一次性后端；
3. 创建随机用户、一次性项目和 `acceptance_scripted` 模型配置；
4. 验证问题、工具权限、命令批准/拒绝在后端重启后仍续跑同一任务；
5. 验证同一 checkout 的竞争任务进入 `WORKSPACE_WAITING`，并校验 `runSession / runMessages / parts` 的真实 API 投影；
6. 验证手动压缩异步任务完成；
7. 删除一次性项目和模型配置；
8. 用普通 `local` profile 重启并确认不会暴露脚本化 Provider。

脚本不会把密码、JWT 或请求头写入磁盘或最终 JSON。后端日志写到系统临时目录，失败时会报告目录位置用于排查。

```powershell
cd D:\LabexAgent\backend
mvn -q -DskipTests package
cd ..
.\scripts\acceptance\agent-runtime.ps1
```

可覆盖参数：

```powershell
.\scripts\acceptance\agent-runtime.ps1 -BackendPort 18080 -JarPath .\backend\target\labex-agent-backend-1.0.0.jar -TimeoutSeconds 120
```

## 单命令完整验收

`run-all.ps1` 会依次执行后端打包、验收辅助模块单测、后端重启验收和真实浏览器验收。默认只使用 18080、13000、19222，不会接管 8080/3000。

```powershell
cd D:\LabexAgent
.\scripts\acceptance\run-all.ps1
```

若 Java、Maven、Node、npm、Chrome/Edge、MySQL 缺失，或验收端口已被占用，脚本会 fail closed 并指出具体 gate。每个运行脚本都在 `finally` 中停止自己启动的进程；浏览器临时 profile 只会在确认路径位于系统临时目录且包含 `labex-agent-browser-` 前缀时删除。
