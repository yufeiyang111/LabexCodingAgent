package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineWorkspacePauseContractTest {
    private final String source = readSource();

    @Test
    void recoverableWorkspaceWaitUsesPausedTransportWithoutTerminalEvents() {
        String method = method("private void waitForProjectCheckout(", "void reportStartupFailure(");

        assertThat(method).contains("\"WORKSPACE_WAITING\"");
        assertThat(method).contains("\"TASK_PAUSED\"");
        assertThat(method).contains("\"workspace_checkout\"");
        assertThat(method).doesNotContain("streamFinal(");
        assertThat(method).doesNotContain("\"DONE\"");
    }

    @Test
    void environmentBlockedToolBatchClosesProviderProtocolBeforePausing() {
        String branch = method("if (environmentBlocker.isPresent()) {", "if (res.isApprovalRequired()) {");

        assertThat(branch).contains("journalToolBlocked(");
        assertThat(branch).contains("appendProviderMessage(");
        assertThat(branch).contains("toolResultMessage(call");
        assertThat(branch).contains("toolResultMessage(skipped");
        assertThat(branch.indexOf("appendProviderMessage("))
                .isLessThan(branch.indexOf("stopForEnvironmentBlocker("));
    }

    @Test
    void recoverableEnvironmentAndContextWaitsUsePausedTransportWithoutTerminalEvents() {
        String environment = method("private void stopForEnvironmentBlocker(", "private void waitForProjectCheckout(");
        String context = method("private void stopForContextLimit(", "private void publishContextStatus(");

        assertRecoverableWait(environment, "ENVIRONMENT_BLOCKED", "environment");
        assertRecoverableWait(context, "CONTEXT_LIMIT_BLOCKED", "context_limit");
    }

    private void assertRecoverableWait(String method, String blockerEvent, String reason) {
        assertThat(method).contains("\"" + blockerEvent + "\"");
        assertThat(method).contains("\"TASK_PAUSED\"");
        assertThat(method).contains("\"" + reason + "\"");
        assertThat(method).doesNotContain("streamFinal(");
        assertThat(method).doesNotContain("\"DONE\"");
    }

    private String method(String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    private String readSource() {
        try {
            return Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
