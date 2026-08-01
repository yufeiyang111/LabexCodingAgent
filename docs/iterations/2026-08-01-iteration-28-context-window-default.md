# 第 28 轮迭代：去除未知上下文的宽松默认

- 日期：2026-08-01
- 范围：前端模型配置、上下文配置门禁、系统验收脚本
- 对应架构约束：`contextWindowTokens` 未知时不得使用 1,000,000 默认

## 1. 问题

前端旧模型配置流程在多处将未配置的上下文窗口补成 `1_000_000`：

- 新建模型配置的 form 默认值；
- 自定义模板默认值；
- 编辑旧配置时的 null fallback；
- 选择模型模板时的 null fallback；
- 输入框 placeholder 也误导用户认为 1,000,000 是通用安全默认。

后端其实已有缺失配置的 fail-closed 逻辑：`context_window_unconfigured` 会阻止 Provider 请求。本轮修复前后端违背的前端默认。

## 2. 实现

### 前端

- `frontend/src/views/CloudWorkspace.vue`
  - 新建、自定义模板、编辑、选择模板均改为保留 `null`；
  - 不再将未知能力伪装为超大上下文。
- `frontend/src/components/cloud/ModelConfigDialog.vue`
  - placeholder 改为“请填写真实值”，明确要求用户提供实际模型能力。
- `frontend/src/components/cloud/ModelConfigDialog.test.mjs`
  - 回归断言改为验证 null 是未知状态，而不是 1,000,000 。

### 系统验收

- `scripts/acceptance/agent-runtime.ps1`
  - 新增一个不配置 `contextWindowTokens` 的 acceptance model config；
  - 发起 Agent 请求后必须收到 `CONTEXT_LIMIT_BLOCKED`；
  - 必须是 `reasonCode=context_window_unconfigured`；
  - 验证 Provider 没有被调用，并在 finally 中清理测试配置。

## 3. 验收结果

### 前端

```text
cd D:\LabexAgent\frontend
npm test
结果：160 项全部通过

npm run build
结果：构建通过，CloudWorkspace 和 index chunk budget 通过
```

### 后端、重启与真实浏览器

```text
D:\LabexAgent\scripts\acceptance\agent-runtime.ps1 -BackendPort 18103 -TimeoutSeconds 120
结果：通过，contextWindowUnconfigured=true

D:\LabexAgent\scripts\acceptance\run-all.ps1 -BackendPort 18104 -FrontendPort 13005 -CdpPort 19231 -TimeoutSeconds 120
结果：打包、acceptance unit tests、后端重启验收、Chrome/CDP 浏览器验收全部通过
```

浏览器验收仍然包含新会话隔离、刷新回放去重、问题回答、审批恢复、多 tool call、compaction、流中断和无 console/network error 验证。

## 4. 剩余风险

- 旧数据库中已经保存的 1,000,000 不是未知值，本轮不会自动改写用户已明确配置的值。
- 用户仍需为每个模型填写与上游 Provider 文档一致的真实上下文窗口。

## 5. 结论

本轮使前后端在“未知模型能力”上使用同一个 fail-closed 契约，不再以假的 1,000,000 容量掩盖配置缺失。
