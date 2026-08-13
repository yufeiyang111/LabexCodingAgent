package com.labex.labexagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionResult;
import org.junit.jupiter.api.Test;

class ToolResultTest {
    @Test
    void zeroProcessExitIsSuccessful() {
        ToolResult result = ToolResult.fromProcessExit(0, "build passed");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("exit=0\nbuild passed");
    }

    @Test
    void nonZeroProcessExitIsFailedAndPreservesOutput() {
        ToolResult result = ToolResult.fromProcessExit(137, "process terminated");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).isEqualTo("exit=137\nprocess terminated");
    }

    @Test
    void successfulExecutionIncludesStructuredMetadata() {
        ProcessExecutionResult execution = new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 125, "tests passed", true);

        ToolResult result = ToolResult.fromProcessExecution(execution);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent())
                .contains("exit=0")
                .contains("status=succeeded")
                .contains("duration_ms=125")
                .contains("truncated=true")
                .endsWith("tests passed");
    }

    @Test
    void structuredProcessResultExposesArtifactAndExecutionEnvironment() {
        ProcessExecutionResult execution = new ProcessExecutionResult(
                ExecutionStatus.FAILED, 2, 321, "build output", true,
                ".labex-agent/artifacts/task-9/call-1.log", 15_000L);

        ToolResult result = ToolResult.fromProcessExecution(execution, "bash", "frontend");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getExecutionStatus()).isEqualTo("failed");
        assertThat(result.getExecutionExitCode()).isEqualTo(2);
        assertThat(result.getExecutionDurationMs()).isEqualTo(321L);
        assertThat(result.isExecutionOutputTruncated()).isTrue();
        assertThat(result.getExecutionOutputChars()).isEqualTo(15_000L);
        assertThat(result.getExecutionOutputPath()).isEqualTo(".labex-agent/artifacts/task-9/call-1.log");
        assertThat(result.getExecutionShell()).isEqualTo("bash");
        assertThat(result.getExecutionWorkdir()).isEqualTo("frontend");
        assertThat(result.getContent()).contains("output_path=.labex-agent/artifacts/task-9/call-1.log")
                .contains("shell=bash")
                .contains("workdir=frontend");
    }

    @Test
    void durableUserInteractionIsASeparateSuspensionState() {
        ToolResult result = ToolResult.interactionRequired("Waiting for answer", "question-1", "question");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isInteractionRequired()).isTrue();
        assertThat(result.getInteractionRequestId()).isEqualTo("question-1");
        assertThat(result.getInteractionType()).isEqualTo("question");
        assertThat(result.isApprovalRequired()).isFalse();
    }

    @Test
    void timedOutExecutionCannotSatisfyVerification() {
        ProcessExecutionResult execution = new ProcessExecutionResult(
                ExecutionStatus.TIMED_OUT, null, 200, "partial output", false);

        ToolResult result = ToolResult.fromProcessExecution(execution);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent())
                .contains("exit=none")
                .contains("status=timed_out")
                .endsWith("partial output");
    }
}
