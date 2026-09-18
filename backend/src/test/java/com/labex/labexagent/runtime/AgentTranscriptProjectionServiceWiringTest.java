package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.labex.labexagent.attachment.AgentInputAttachmentService;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.service.AgentConversationMemoryProjectionService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentModelConfigMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.AgentModelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentTranscriptProjectionServiceWiringTest {

    @Test
    void createsProjectionServiceWithItsDurableTranscriptDependency() {
        new ApplicationContextRunner()
                .withBean(AgentRunTranscriptService.class, () -> mock(AgentRunTranscriptService.class))
                .withBean(AgentProviderMessageProjector.class, AgentProviderMessageProjector::new)
                .withBean(AgentCompactionService.class, () -> mock(AgentCompactionService.class))
                .withBean(AgentInputAttachmentService.class, () -> mock(AgentInputAttachmentService.class))
                .withBean(AgentTaskMapper.class, () -> mock(AgentTaskMapper.class))
                .withBean(AgentConversationMapper.class, () -> mock(AgentConversationMapper.class))
                .withBean(AgentConversationMemoryProjectionService.class,
                        () -> mock(AgentConversationMemoryProjectionService.class))
                .withBean(AgentConversationMessageGraphProjector.class,
                        () -> mock(AgentConversationMessageGraphProjector.class))
                // 占位化判据参数、token 估算与模型级 prune 开关都是 Provider 投影的必需协作者：
                // 缺少它们时装配必须失败，而不是让 Provider 请求静默退化。
                .withBean(AgentLoopProperties.class, AgentLoopProperties::new)
                .withBean(AgentRequestTokenEstimator.class, AgentRequestTokenEstimator::new)
                .withBean(AgentModelConfigMapper.class, () -> mock(AgentModelConfigMapper.class))
                .withBean(AgentModelConfigService.class, () -> mock(AgentModelConfigService.class))
                .withBean(AgentTranscriptProjectionService.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(AgentTranscriptProjectionService.class));
    }
}
