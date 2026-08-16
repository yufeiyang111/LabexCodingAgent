package com.labex.labexagent.runtime.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.labex.entity.AgentTask;
import org.junit.jupiter.api.Test;

class AgentRuntimeProfileResolverTest {

    @Test
    void executionAlwaysUsesTheDurableTaskSnapshotRatherThanLegacyPayload() {
        AgentTask task = new AgentTask();
        task.setRuntimeProfile("labex-native");
        task.setRequestPayload("{\"runtimeProfile\":\"labex-legacy\"}");

        assertEquals(AgentRuntimeProfile.LABEX_NATIVE,
                AgentRuntimeProfileResolver.resolveExecutionProfile(task));
    }

    @Test
    void historicTaskWithoutASnapshotStaysOnLegacyEvenIfOtherStateChangesLater() {
        AgentTask task = new AgentTask();
        task.setRequestPayload("{\"runtimeProfile\":\"labex-native\"}");

        assertEquals(AgentRuntimeProfile.LABEX_LEGACY,
                AgentRuntimeProfileResolver.resolveExecutionProfile(task));
    }
}
