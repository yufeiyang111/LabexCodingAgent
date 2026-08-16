package com.labex.labexagent.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.ExecutionFence;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AgentProviderTranscriptAppenderTest {

    @Test
    void copiesTheProviderMessageAndAppendsItAtTheDurableTranscriptTail() {
        AgentProviderMessageProjector projector = mock(AgentProviderMessageProjector.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentProviderTranscriptAppender appender = new AgentProviderTranscriptAppender(projector, transcript);
        ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);
        Map<String, Object> source = Map.of("role", "tool", "tool_call_id", "call-1", "name", "read_file", "content", "ok");
        Map<String, Object> durableCopy = new LinkedHashMap<>(source);
        when(projector.copyMessage(source)).thenReturn(durableCopy);
        when(transcript.nextSequence(71L)).thenReturn(18L);

        appender.append(fence, 71L, 4L, source);

        InOrder order = inOrder(projector, transcript);
        order.verify(projector).copyMessage(source);
        order.verify(transcript).nextSequence(71L);
        order.verify(transcript).appendMessage(eq(fence), eq(71L), eq(4L), eq(18L), eq(durableCopy));
    }
}
