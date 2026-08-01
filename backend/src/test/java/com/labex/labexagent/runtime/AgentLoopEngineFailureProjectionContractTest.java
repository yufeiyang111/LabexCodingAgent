package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineFailureProjectionContractTest {

    @Test
    void everyAgentLoopFailureUsesTheAuthoritativePersistedEventProjection() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"), StandardCharsets.UTF_8);

        assertThat(source).doesNotContain("this.taskService.updateTask(task.getTaskId(), \"failed\"");
        assertThat(count(source, "this.failTaskAndProject(sse, conv, task,")).isGreaterThanOrEqualTo(6);
    }

    private int count(String source, String marker) {
        int matches = 0;
        int index = 0;
        while ((index = source.indexOf(marker, index)) >= 0) {
            matches++;
            index += marker.length();
        }
        return matches;
    }
}