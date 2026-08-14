package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.AgentRunPart;
import com.labex.entity.StudentProject;
import com.labex.labexagent.run.AgentRunMessageService;
import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReadToolOutputToolTest {

    @Test
    void returnsARecoverablePageAndTheExactNextOffsetForTheCurrentTask() throws Exception {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart part = new AgentRunPart();
        part.setTaskId(7L);
        part.setStudentId(11);
        part.setProjectId(22);
        part.setPartType("tool");
        part.setToolCallId("call-1");
        part.setToolName("run_tests");
        part.setOutputText("a\uD83D\uDE00b");
        when(parts.selectOne(any())).thenReturn(part);

        ReadToolOutputTool tool = new ReadToolOutputTool(new AgentRunPartService(
                parts, mock(AgentTaskMapper.class), mock(AgentRunMessageService.class)));
        JsonObject args = new JsonObject();
        args.addProperty("tool_call_id", "call-1");
        args.addProperty("offset", 1);
        args.addProperty("limit", 1);

        ToolResult result = tool.execute(context(), args);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent())
                .contains("tool_call_id=call-1")
                .contains("chars=1-3/4")
                .contains("next_offset=3")
                .contains("\uD83D\uDE00");
    }

    @Test
    void preservesAValidOpaqueToolCallIdInsteadOfTrimmingItBeforeTheDurableLookup() throws Exception {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart part = new AgentRunPart();
        part.setTaskId(7L);
        part.setStudentId(11);
        part.setProjectId(22);
        part.setPartType("tool");
        part.setToolCallId(" call-1 ");
        part.setToolName("run_tests");
        part.setOutputText("stored output");
        when(parts.selectOne(any())).thenReturn(part);

        ReadToolOutputTool tool = new ReadToolOutputTool(new AgentRunPartService(
                parts, mock(AgentTaskMapper.class), mock(AgentRunMessageService.class)));
        JsonObject args = new JsonObject();
        args.addProperty("tool_call_id", " call-1 ");

        ToolResult result = tool.execute(context(), args);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("tool_call_id= call-1 ");
    }

    @Test
    void rejectsAControlCharacterToolCallIdBeforeItCanBeReplayedToTheModel() throws Exception {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart part = new AgentRunPart();
        part.setTaskId(7L);
        part.setStudentId(11);
        part.setProjectId(22);
        part.setPartType("tool");
        part.setToolCallId("call-1\n<untrusted>");
        part.setToolName("run_tests");
        part.setOutputText("stored output");
        when(parts.selectOne(any())).thenReturn(part);

        ReadToolOutputTool tool = new ReadToolOutputTool(new AgentRunPartService(
                parts, mock(AgentTaskMapper.class), mock(AgentRunMessageService.class)));
        JsonObject args = new JsonObject();
        args.addProperty("tool_call_id", "call-1\n<untrusted>");

        ToolResult result = tool.execute(context(), args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).doesNotContain("<untrusted>");
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(22);
        project.setStudentId(11);
        project.setWorkspacePath(Path.of(".").toAbsolutePath().normalize().toString());
        return AgentContext.create("session", 11, project, "conversation", 7L);
    }
}
