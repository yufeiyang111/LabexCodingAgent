package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.mapper.AgentVerificationMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BackgroundDeliveryServiceTest {
    @Test void deniesCommitWhenTaskHasNoPassedServerSideVerification() {
        AgentVerificationMapper verifications = mock(AgentVerificationMapper.class);
        when(verifications.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        BackgroundDeliveryService service = new BackgroundDeliveryService(new BackgroundDeliveryPolicy(), mock(AgentRunArtifactService.class), verifications);
        assertThrows(IllegalStateException.class, () -> service.commit(9L, Path.of("."), "agent/run-9", "message", true));
    }
}
