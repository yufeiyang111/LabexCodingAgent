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
        assertDoesNotThrow(() -> policy.validateSpawn(null, 3, 100));
    }

    @Test
    void rejectsNestedAndOverLimitSpawn() {
        assertThrows(IllegalStateException.class, () -> policy.validateSpawn(1L, 0, 100));
        assertThrows(IllegalStateException.class, () -> policy.validateSpawn(null, 4, 100));
    }

    @Test
    void isRegisteredForInjectionIntoSubagentServices() {
        assertTrue(SubagentPolicy.class.isAnnotationPresent(Component.class));
    }
}