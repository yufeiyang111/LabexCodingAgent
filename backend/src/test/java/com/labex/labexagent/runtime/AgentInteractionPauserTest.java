package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AgentInteractionPauserTest {
    @Test
    void persistsPermissionAndQuestionPauseStates() {
        AgentTaskService tasks = Mockito.mock(AgentTaskService.class);
        AgentInteractionPauser pauser = new AgentInteractionPauser(tasks);

        var permission = pauser.pause(7L, ToolResult.interactionRequired("wait", "req-1", "permission"), "zh");
        var question = pauser.pause(8L, ToolResult.interactionRequired("wait", "req-2", "question"), "en");

        assertEquals("waiting_approval", permission.state());
        assertEquals("waiting_user", question.state());
        verify(tasks).updateTask(7L, "waiting_approval", permission.title(), permission.detail());
        verify(tasks).updateTask(8L, "waiting_user", question.title(), question.detail());
    }
}

