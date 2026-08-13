package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.labexagent.run.AgentVerificationRecorder;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.mapper.AgentVerificationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RunTestsToolWiringTest {

    @Test
    void createsTheToolUsingItsPersistenceAwareConstructor() {
        new ApplicationContextRunner()
                .withBean(SandboxWorker.class, () -> mock(SandboxWorker.class))
                .withBean(AgentVerificationMapper.class, () -> mock(AgentVerificationMapper.class))
                .withBean(AgentRunArtifactService.class, () -> mock(AgentRunArtifactService.class))
                .withBean(AgentVerificationRecorder.class)
                .withBean(RunTestsTool.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentVerificationRecorder.class);
                    assertThat(context).hasSingleBean(RunTestsTool.class);
                });
    }
}