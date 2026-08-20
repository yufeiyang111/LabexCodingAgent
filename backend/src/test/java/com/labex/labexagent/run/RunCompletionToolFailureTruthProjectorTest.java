package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.labex.entity.AgentRunPart;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunCompletionToolFailureTruthProjectorTest {

    @Test
    void nonZeroShellOutcomeRemainsAnUnresolvedCompletionBlockerEvenWhenToolTransportCompleted() {
        RunCompletionToolFailureTruthProjector projector = new RunCompletionToolFailureTruthProjector();

        List<String> failures = projector.unresolvedFailureLabels(List.of(
                toolPart(1L, "completed", "shell", "{\"command\":\"git status\"}",
                        "{\"execution\":{\"status\":\"failed\",\"exitCode\":128},\"failureClass\":\"non_zero_exit\"}"),
                toolPart(2L, "completed", "shell", "{\"command\":\"pwd\"}",
                        "{\"execution\":{\"status\":\"succeeded\",\"exitCode\":0}}")));

        assertEquals(List.of("shell (non_zero_exit)"), failures);
    }

    @Test
    void exactSuccessfulRetryResolvesOnlyTheMatchingDurableToolFailure() {
        RunCompletionToolFailureTruthProjector projector = new RunCompletionToolFailureTruthProjector();

        List<String> failures = projector.unresolvedFailureLabels(List.of(
                toolPart(1L, "completed", "shell", "{\"command\":\"npm test\"}",
                        "{\"execution\":{\"status\":\"failed\",\"exitCode\":1},\"failureClass\":\"non_zero_exit\"}"),
                toolPart(2L, "completed", "shell", "{\"command\":\"npm run build\"}",
                        "{\"execution\":{\"status\":\"succeeded\",\"exitCode\":0}}"),
                toolPart(3L, "completed", "shell", "{\"command\":\"npm test\"}",
                        "{\"execution\":{\"status\":\"succeeded\",\"exitCode\":0}}")));

        assertEquals(List.of(), failures);
    }

    @Test
    void permissionAndToolExceptionsRemainExplicitFailureFacts() {
        RunCompletionToolFailureTruthProjector projector = new RunCompletionToolFailureTruthProjector();

        List<String> failures = projector.unresolvedFailureLabels(List.of(
                toolPart(1L, "failed", "write_file", "{\"path\":\"src/App.vue\"}",
                        "{\"failureClass\":\"permission_denied\"}"),
                toolPart(2L, "failed", "lsp", "{\"operation\":\"diagnostics\"}",
                        "{\"failureClass\":\"tool_error\"}")));

        assertEquals(List.of("write_file (permission_denied)", "lsp (tool_error)"), failures);
    }

    @Test
    void successfulWriteFileResolvesPriorApplyPatchFailureOnSameFile() {
        RunCompletionToolFailureTruthProjector projector = new RunCompletionToolFailureTruthProjector();

        List<String> failures = projector.unresolvedFailureLabels(List.of(
                toolPart(1L, "failed", "apply_patch", "{\"path\":\"frontend/public/index.html\"}",
                        "{\"failureClass\":\"tool_error\"}"),
                toolPart(2L, "completed", "write_file", "{\"file_path\":\"frontend/public/index.html\"}",
                        "{\"execution\":{\"status\":\"succeeded\"}}")));

        assertEquals(List.of(), failures);
    }

    private AgentRunPart toolPart(Long partId, String status, String toolName, String inputJson, String metadata) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(partId);
        part.setPartType("tool");
        part.setStatus(status);
        part.setToolName(toolName);
        part.setInputJson(inputJson);
        part.setMetadata(metadata);
        return part;
    }
}
