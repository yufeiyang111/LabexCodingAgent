package com.labex.labexagent.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.labex.mapper.AgentInputAttachmentMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentInputAttachmentHydrationTest {

    private final AgentInputAttachmentMapper attachmentMapper = mock(AgentInputAttachmentMapper.class);
    private final AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
    private final AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
    private final AgentInputAttachmentService service = new AgentInputAttachmentService(
            attachmentMapper, taskMapper, modelConfigService, new AgentAttachmentProperties());

    @Test
    void consumedImageCarriersDegradeToTextPlaceholderWithoutTouchingStorage() {
        Map<String, Object> durable = Map.of(
                "role", "user", "content", "inspect this architecture",
                "attachmentIds", List.of("image-1", "image-2"));

        Map<String, Object> degraded = service.hydrateProviderMessage(9L, durable, true);

        assertThat(degraded.get("role")).isEqualTo("user");
        assertThat(String.valueOf(degraded.get("content")))
                .startsWith("inspect this architecture")
                .contains("[Image attachments from an earlier turn were already provided to the model");
        assertThat(degraded.containsKey("attachmentIds")).isFalse();
        verifyNoInteractions(attachmentMapper);
    }

    @Test
    void unconsumedImageCarriersStillRequireADurableTaskId() {
        Map<String, Object> durable = Map.of(
                "role", "user", "content", "fresh screenshot", "attachmentIds", List.of("image-new"));

        assertThatThrownBy(() -> service.hydrateProviderMessage(null, durable, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Attachment projection requires a durable task ID");
    }

    @Test
    void messagesWithoutAttachmentsPassThroughRegardlessOfConsumption() {
        Map<String, Object> plainUser = Map.of("role", "user", "content", "plain text");
        Map<String, Object> assistant = Map.of("role", "assistant", "content", "reply");

        // 无附件 user 消息走 withoutAttachmentIds 拷贝路径：内容保持一致且绝不出现 attachmentIds。
        Map<String, Object> projectedUser = service.hydrateProviderMessage(9L, plainUser, true);
        assertThat(projectedUser).isEqualTo(plainUser);
        assertThat(projectedUser.containsKey("attachmentIds")).isFalse();
        // 非 user 角色原样返回。
        assertThat(service.hydrateProviderMessage(9L, assistant, true)).isSameAs(assistant);
        verifyNoInteractions(attachmentMapper);
    }
}
