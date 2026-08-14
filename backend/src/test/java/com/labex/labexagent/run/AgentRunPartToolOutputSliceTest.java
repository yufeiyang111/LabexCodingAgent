package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunPart;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import org.junit.jupiter.api.Test;

class AgentRunPartToolOutputSliceTest {

    @Test
    void readsTheDurableRawOutputBySafeCharacterPagesWithoutSplittingEmoji() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunPart stored = storedPart("a\uD83D\uDE00b");
        when(parts.selectOne(any())).thenReturn(stored);

        AgentRunPartService service = new AgentRunPartService(parts, mock(AgentTaskMapper.class), mock(AgentRunMessageService.class));

        AgentRunPartService.ToolOutputSlice slice = service.readOwnedToolOutput(11, 22, 7L, "call-1", 1, 1);

        assertThat(slice.content()).isEqualTo("\uD83D\uDE00");
        assertThat(slice.offset()).isEqualTo(1);
        assertThat(slice.nextOffset()).isEqualTo(3);
        assertThat(slice.totalChars()).isEqualTo(4);
        assertThat(slice.hasMore()).isTrue();
    }

    @Test
    void refusesRawOutputThatDoesNotBelongToTheActiveStudentAndProject() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        when(parts.selectOne(any())).thenReturn(storedPart("private output"));
        AgentRunPartService service = new AgentRunPartService(parts, mock(AgentTaskMapper.class), mock(AgentRunMessageService.class));

        assertThat(assertThrows(IllegalArgumentException.class,
                () -> service.readOwnedToolOutput(99, 22, 7L, "call-1", 0, 100)))
                .hasMessageContaining("active task");
    }

    @Test
    void rejectsNegativePageLimitsInsteadOfTurningThemIntoAnUnboundedRead() {
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        when(parts.selectOne(any())).thenReturn(storedPart("raw output"));
        AgentRunPartService service = new AgentRunPartService(parts, mock(AgentTaskMapper.class), mock(AgentRunMessageService.class));

        assertThat(assertThrows(IllegalArgumentException.class,
                () -> service.readOwnedToolOutput(11, 22, 7L, "call-1", 0, -1)))
                .hasMessageContaining("limit");
    }

    private AgentRunPart storedPart(String output) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(91L);
        part.setTaskId(7L);
        part.setStudentId(11);
        part.setProjectId(22);
        part.setPartType("tool");
        part.setToolCallId("call-1");
        part.setToolName("run_tests");
        part.setOutputText(output);
        return part;
    }
}
