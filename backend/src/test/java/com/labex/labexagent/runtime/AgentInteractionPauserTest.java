package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
        verify(tasks).updateTask(eq(7L), eq("waiting_approval"), eq(permission.title()), eq(permission.detail()),
                startsWith("task-7-pause-"));
        verify(tasks).updateTask(eq(8L), eq("waiting_user"), eq(question.title()), eq(question.detail()),
                startsWith("task-8-pause-"));
    }
}

