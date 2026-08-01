# 第 32 轮：删除 AgentContext 内存 transcript 事实

- 日期：2026-08-01
- 前置提交：`7b7c1f1 fix: stabilize lease recovery and restart projection`
- 目标：防止 AgentContext 重新成为 Provider 历史的隐形事实源

## 发现

全仓库审计显示：`AgentContext.getTranscript()` 和 `setTranscript()` 没有任何业务调用点，只存在于对象自身。同时，`AgentLoopEngine` 的 Provider 请求已经从 `AgentTranscriptProjectionService` 加载持久 projection，该字段已是无用的并列内存历史。

先增加回归测试 `AgentContextTranscriptBoundaryTest`，确认字段尚存在时测试失败；再执行删除。

## 实现

- 删除 `AgentContext.transcript` 字段、getter/setter、equals/hashCode/toString 序列化参与。
- 简化 `AgentContext` 构造函数，删除所有测试中的虚假 transcript 初始化参数。
- 增加反回归断言：未来不能把内存 Provider transcript 加回这个上下文对象。

## 验证

首先执行回归测试，按预期失败：

```text
AgentContextTranscriptBoundaryTest: 1 test, 1 failure
reason: transcript field was still present
```

删除后执行受影响测试：

```text
AgentContextTranscriptBoundaryTest + 13 related runtime/tool tests
result: passed
```

还需执行后端全量 Maven 测试作为本轮提交前门禁。

## 边界

本轮只删除已确认无调用的字段，没有改动尚未完成的 `TranscriptMessageList` 兼容投影；后续将单独设计并验证该路径的移除。

`D:/LabexAgent/backend/src/main/resources/application-acceptance.yml` 以及其他无关工作区改动保持未提交。
