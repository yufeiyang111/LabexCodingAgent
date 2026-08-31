package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SubagentDepthAndCompletionTest {

    @Test
    void depthPolicyAllowsUpToMaxSpawnDepth() {
        SubagentPolicy policy = new SubagentPolicy();
        // 主 Agent(0) → 子(1)、孙(2)、曾孙(3) 均允许；第 4 层被拒。
        policy.validateSpawn(0, 0, 100);
        policy.validateSpawn(1, 0, 100);
        policy.validateSpawn(2, 0, 100);
        assertThrows(IllegalStateException.class, () -> policy.validateSpawn(3, 0, 100));
        // 并行与预算约束保留。
        assertThrows(IllegalStateException.class, () -> policy.validateSpawn(0, policy_maxParallel(), 100));
        assertThrows(IllegalArgumentException.class, () -> policy.validateSpawn(0, 0, 0));
    }

    private int policy_maxParallel() {
        return new AgentSubagentProperties().getMaxParallel();
    }

    @Test
    void completionRegistryBindsTaskIdRunsFinisherThenCompletes() {
        SubagentCompletionRegistry registry = new SubagentCompletionRegistry();
        CompletableFuture<Void> future = registry.register(7L,
                (rowId, task) -> task.setParentTaskId(rowId),
                childTaskId -> { });

        com.labex.entity.AgentTask task = new com.labex.entity.AgentTask();
        task.setTaskId(99L);
        registry.onCreated(7L, task);
        assertEquals(7L, task.getParentTaskId());
        assertFalse(future.isDone());

        long[] finisherArg = {-1};
        registry = new SubagentCompletionRegistry();
        CompletableFuture<Void> future2 = registry.register(8L,
                (rowId, t) -> { },
                childTaskId -> finisherArg[0] = childTaskId);
        com.labex.entity.AgentTask task2 = new com.labex.entity.AgentTask();
        task2.setTaskId(100L);
        registry.onCreated(8L, task2);
        registry.finished(100L);
        assertTrue(future2.isDone());
        assertEquals(100L, finisherArg[0]);
        // 未注册任务零影响。
        registry.finished(424242L);
    }

    @Test
    void completionRegistryFailedCompletesExceptionally() {
        SubagentCompletionRegistry registry = new SubagentCompletionRegistry();
        CompletableFuture<Void> future = registry.register(9L, (r, t) -> { }, id -> { });
        RuntimeException failure = new RuntimeException("queue rejected");
        registry.failed(9L, failure);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void subagentTypeParsingIsTotal() {
        assertEquals(SubagentType.EXPLORE, SubagentType.parse("Explore"));
        assertEquals(SubagentType.SCOUT, SubagentType.parse("scout"));
        assertEquals(SubagentType.GENERAL, SubagentType.parse("unknown"));
        assertEquals(SubagentType.GENERAL, SubagentType.parse(null));
        assertFalse(SubagentType.GENERAL.readOnly());
        assertTrue(SubagentType.EXPLORE.readOnly());
        assertTrue(SubagentType.SCOUT.readOnly());
    }
}
