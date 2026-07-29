package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineWorkspacePauseContractTest {
    @Test
    void recoverableWorkspaceWaitUsesPausedTransportWithoutTerminalEvents() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private void waitForProjectCheckout(");
        int end = source.indexOf("void reportStartupFailure(", start);
        String method = source.substring(start, end);

        assertThat(method).contains("\"WORKSPACE_WAITING\"");
        assertThat(method).contains("\"TASK_PAUSED\"");
        assertThat(method).contains("\"workspace_checkout\"");
        assertThat(method).doesNotContain("streamFinal(");
        assertThat(method).doesNotContain("\"DONE\"");
    }
}
