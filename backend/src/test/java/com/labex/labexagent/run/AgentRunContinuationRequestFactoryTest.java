package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import org.junit.jupiter.api.Test;

class AgentRunContinuationRequestFactoryTest {
    @Test
    void preservesTheOriginalUserObjectiveWhenAQueuedTaskResumes() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setSessionId("session-1");
        task.setConversationId("conversation-1");
        task.setMode("agent");
        task.setRequestPayload("{\"message\":\"[acceptance:isolation:B-ONLY]\",\"modelConfigId\":19,\"activePath\":\"README.md\"}");

        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task,
                "The shared checkout is available again.");

        assertThat(request.getMessage()).contains("[acceptance:isolation:B-ONLY]")
                .contains("The shared checkout is available again.");
        assertThat(request.getSessionId()).isEqualTo("session-1");
        assertThat(request.getConversationId()).isEqualTo("conversation-1");
        assertThat(request.getResumeTaskId()).isEqualTo(71L);
        assertThat(request.getModelConfigId()).isEqualTo(19);
        assertThat(request.getActivePath()).isEqualTo("README.md");
    }

    @Test
    void fallsBackSafelyWhenAnOlderTaskHasNoStructuredPayload() {
        AgentTask task = new AgentTask();
        task.setTaskId(72L);
        task.setSessionId("session-2");
        task.setConversationId("conversation-2");
        task.setRequestPayload("not-json");

        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task, "Resume safely.");

        assertThat(request.getMessage()).contains("Continue the existing task").contains("Resume safely.");
    }
}
