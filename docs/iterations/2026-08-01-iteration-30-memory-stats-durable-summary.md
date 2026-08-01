# 第 30 轮迭代：上下文统计改为 durable summary 投影

- 日期：2026-08-01
- 范围：上下文使用量统计、AgentConversation 旧 summary 读取审计
- 对应约束：派生 summary 只能作为缓存或投影，不能作为持久上下文事实源

## 1. 审计发现

前端对话历史仍通过 `agentMessages` 加载历史投影，活动 task 则通过 `active-task` 加载 durable Message/Part 并重新连接事件流。这条兼容路径暂不删除，因为完全切换还需要任务历史投影的后端契约和 shadow compare。

但发现 `AgentConversationService.getMemoryStats()` 仍然读取 `AgentConversation.summary` 计算上下文长度和是否需要压缩。这会让前端上下文面板显示过期数据，与 AgentLoop 已切换的 durable transcript 路径不一致。

## 2. 实现

修改：

- `backend/src/main/java/com/labex/labexagent/service/AgentConversationService.java`
  - `getMemoryStats()` 改为读取最新的 `COMPACTION_SUMMARY` AgentMessage；
  - 没有 durable compaction 时使用空摘要，不回退到 conversation aggregate summary。
- `backend/src/test/java/com/labex/labexagent/service/AgentConversationServiceCompactionTest.java`
  - 新增 `memoryStatsUseDurableCompactionSummaryInsteadOfLegacyAggregateCache`；
  - 先在旧实现上验证失败，修复后验证通过。

## 3. 验收结果

```text
cd D:\LabexAgent\backend
mvn -q "-Dtest=AgentConversationServiceCompactionTest" test
结果：通过

mvn -q test
结果：825 项测试，0 failures，0 errors，8 skipped
```

```text
cd D:\LabexAgent\frontend
npm test
结果：160 pass，0 fail

npm run build
结果：通过，chunk budget 通过
```

```text
D:\LabexAgent\scripts\acceptance\run-all.ps1 -BackendPort 18108 -FrontendPort 13009 -CdpPort 19235 -TimeoutSeconds 120
结果：package、acceptance unit tests、backend restart acceptance、browser acceptance 全部通过。
浏览器：conversationIsolation、refreshReplayDeduplicated、permissionApprovalRefreshRecovery、durableCompaction、providerStreamInterruptionHandled 均为 true，consoleErrors=0，networkErrors=0。
```

## 4. 剩余工作

- 旧 `AgentConversation.summary` 写入仍作为兼容投影保留，需要监控无新写入后再删除。
- 前端历史消息的完全 durable task projection 还需后续新增历史任务投影契约，不在本轮混合切换。
