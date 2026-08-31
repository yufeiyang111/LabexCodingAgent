package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class SubagentPolicyTest {
    private final SubagentPolicy policy = new SubagentPolicy();

    @Test
    void allowsBoundedTopLevelSpawn() {
        // null 视为主 Agent（深度 0），可派发第 1 层子代理。
        assertDoesNotThrow(() -> policy.validateSpawn(null, 3, 100));
    }

    @Test
    void rejectsBeyondDepthLimitAndOverParallel() {
        int maxDepth = new AgentSubagentProperties().getMaxSpawnDepth();
        assertDoesNotThrow(() -> policy.validateSpawn(maxDepth - 1, 0, 100));
        assertThrows(IllegalStateException.class, () -> policy.validateSpawn(maxDepth, 0, 100));
        int maxParallel = new AgentSubagentProperties().getMaxParallel();
        assertThrows(IllegalStateException.class, () -> policy.validateSpawn(null, maxParallel, 100));
    }

    @Test
    void isRegisteredForInjectionIntoSubagentServices() {
        assertTrue(SubagentPolicy.class.isAnnotationPresent(Component.class));
    }
}
