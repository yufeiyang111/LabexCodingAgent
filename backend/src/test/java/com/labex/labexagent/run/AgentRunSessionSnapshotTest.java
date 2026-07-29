package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.AgentTask;
import org.junit.jupiter.api.Test;

class AgentRunSessionSnapshotTest {
    @Test
    void mapsThePersistedConversationToTheCanonicalSessionIdentity() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setConversationId("conversation-71");
        task.setSessionId("execution-71");
        task.setStatus("running");
        task.setRunVersion(8L);
        task.setLastEventSequence(42L);

        AgentRunSessionSnapshot snapshot = AgentRunSessionSnapshot.from(task);

        assertThat(snapshot.sessionId()).isEqualTo("conversation-71");
        assertThat(snapshot.executionSessionId()).isEqualTo("execution-71");
        assertThat(snapshot.toPayload()).containsEntry("lastEventSequence", 42L);
    }
}