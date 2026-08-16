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
        task.setRequestPayload("{\"message\":\"[acceptance:isolation:B-ONLY]\","
                + "\"displayMessage\":\"resume original task\",\"modelConfigId\":19,\"activePath\":\"README.md\"}");

        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task,
                "The shared checkout is available again.");

        assertThat(request.getMessage()).isEqualTo("[acceptance:isolation:B-ONLY]");
        assertThat(request.getDisplayMessage()).isEqualTo("resume original task");
        assertThat(request.userVisibleMessage()).isEqualTo("resume original task");
        assertThat(request.getResumeNote()).isEqualTo("The shared checkout is available again.");
        assertThat(request.getMessage()).doesNotContain("Original user objective", "Durable continuation context");
        assertThat(request.getSessionId()).isEqualTo("session-1");
        assertThat(request.getConversationId()).isEqualTo("conversation-1");
        assertThat(request.getResumeTaskId()).isEqualTo(71L);
        assertThat(request.getModelConfigId()).isEqualTo(19);
        assertThat(request.getActivePath()).isEqualTo("README.md");
    }

    @Test
    void rebuildsContinuationWithTheDurableTaskRuntimeProfileInsteadOfPayloadValue() {
        AgentTask task = new AgentTask();
        task.setTaskId(75L);
        task.setSessionId("session-5");
        task.setConversationId("conversation-5");
        task.setMode("build");
        task.setRuntimeProfile("labex-native");
        task.setRequestPayload("{\"message\":\"resume native task\",\"runtimeProfile\":\"labex-legacy\"}");

        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task, "Resume.");

        assertThat(request.getRuntimeProfile()).isEqualTo("labex-native");
    }
    @Test
    void fallsBackSafelyWhenAnOlderTaskHasNoStructuredPayload() {
        AgentTask task = new AgentTask();
        task.setTaskId(72L);
        task.setSessionId("session-2");
        task.setConversationId("conversation-2");
        task.setRequestPayload("not-json");

        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task, "Resume safely.");

        assertThat(request.getMessage()).isEqualTo("Continue the existing task from durable state.");
        assertThat(request.getResumeNote()).isEqualTo("Resume safely.");
        assertThat(request.getMessage()).doesNotContain("Resume safely.");
    }

    @Test
    void prefersTheDurableTaskModelConfigColumnOverTheLegacyPayloadValue() {
        AgentTask task = new AgentTask();
        task.setTaskId(73L);
        task.setSessionId("session-3");
        task.setConversationId("conversation-3");
        task.setMode("build");
        task.setModelConfigId(77);
        task.setRequestPayload("{\"message\":\"hello\",\"modelConfigId\":19}");

        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task, "Resume.");

        assertThat(request.getModelConfigId()).isEqualTo(77);
    }

    @Test
    void carriesNullModelConfigWhenNeitherColumnNorPayloadExistsSoResolutionFailsClosed() {
        AgentTask task = new AgentTask();
        task.setTaskId(74L);
        task.setSessionId("session-4");
        task.setConversationId("conversation-4");
        task.setRequestPayload("{\"message\":\"legacy task without any model selection\"}");

        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task, "Resume.");

        // 列与 payload 都为 null：请求层不编造模型；恢复解析层据此 fail closed，
        // 绝不会静默回退到用户当前默认模型。
        assertThat(request.getModelConfigId()).isNull();
    }
}
