package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunArtifact;
import com.labex.mapper.AgentRunArtifactMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentRunArtifactServiceTest {
    @Test
    void deterministicRecordReusesArtifactWithSameHash() {
        AgentRunArtifactMapper mapper = Mockito.mock(AgentRunArtifactMapper.class);
        AgentRunArtifact existing = new AgentRunArtifact();
        existing.setSha256(AgentRunArtifactService.sha256("payload"));
        when(mapper.selectOne(any())).thenReturn(existing);

        AgentRunArtifact result = new AgentRunArtifactService(mapper)
                .recordDeterministic(7L, "completion_evidence", "run", "payload");

        assertSame(existing, result);
        verify(mapper, never()).insert(any());
    }
}
