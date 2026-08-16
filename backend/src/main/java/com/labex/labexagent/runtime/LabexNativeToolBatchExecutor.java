package com.labex.labexagent.runtime;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.EnvironmentBlockerClassifier;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Native structured tool-call 的 durable 批处理编排。
 *
 * <p>一个模型 turn 的所有 tool call 必须先写入 assistant transcript 与 Tool Part，随后才按照
 * Provider 返回顺序串行执行。等待审批、用户交互、环境阻断、循环阻断或取消会为未执行的调用写入
 * 明确终态，避免模型、浏览器和恢复逻辑看到半截且无归属的 tool batch。</p>
 *
 * <p>具体工具执行、循环判定、SSE 投影和生命周期迁移仍由上层调用方拥有；本组件只收敛
 * structured batch 的 durable 协议、串行顺序与 Part 状态机。</p>
 */
@Service
public final class LabexNativeToolBatchExecutor {
    private static final String CANCELLATION_DETAIL =
            "Interrupted because the run was cancelled before this tool call started.";

    private final AgentToolCallBatchProtocol protocol;
    private final AgentProviderTranscriptAppender transcriptAppender;
    private final AgentToolCallJournalService toolCallJournalService;

    public LabexNativeToolBatchExecutor(AgentToolCallBatchProtocol protocol,
                                        AgentProviderTranscriptAppender transcriptAppender,
                                        AgentToolCallJournalService toolCallJournalService) {
        this.protocol = Objects.requireNonNull(protocol, "protocol is required");
        this.transcriptAppender = Objects.requireNonNull(transcriptAppender, "transcriptAppender is required");
        this.toolCallJournalService = Objects.requireNonNull(toolCallJournalService,
                "toolCallJournalService is required");
    }

    /**
     * 执行一个已经完成参数/schema admission 的 native tool batch。
     *
     * <p>调用方提供的 delegate 是唯一的实际工具执行入口，因此文件快照、权限、post-edit hook、
     * 指标与循环守卫仍复用既有 AgentLoopEngine 路径。这里保证 delegate 永远不会在整批 pending
     * Part 写入前被调用。</p>
     */
    public BatchResult execute(BatchRequest request, ToolExecutionDelegate delegate,
                               ToolResultProjection resultProjection) throws Exception {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(delegate, "delegate is required");
        Objects.requireNonNull(resultProjection, "resultProjection is required");
        request.validate();

        for (Admission admission : request.admissions()) {
            String identityError = this.protocol.validateIdentity(admission.call());
            if (!identityError.isEmpty()) {
                throw new IllegalArgumentException("Invalid native tool call: " + identityError);
            }
        }

        // Provider assistant turn 是 toolCallId 配对的源头，必须先于任意 Tool Part / 执行结果持久化。
        this.transcriptAppender.append(request.executionFence(), request.taskId(), request.transcriptEpoch(),
                this.protocol.assistantMessage(request.assistantContent(),
                        request.admissions().stream().map(Admission::call).toList()));

        // 所有 admission 先入 durable Part，任何一个工具都不得抢在同批后续调用落库前执行。
        for (Admission admission : request.admissions()) {
            if (admission.allowed()) {
                this.toolCallJournalService.pending(request.executionFence(), request.taskId(),
                        admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                        request.iteration());
            } else {
                this.journalResult(request, admission, admission.rejection());
            }
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (int index = 0; index < request.admissions().size(); index++) {
            Admission admission = request.admissions().get(index);
            if (request.cancellationProbe().isCancellationRequested()) {
                this.interruptRemaining(request, outcomes, index, resultProjection);
                return BatchResult.terminal(Terminal.CANCELLED, outcomes, null);
            }

            if (!admission.allowed()) {
                ToolResult rejection = admission.rejection();
                this.appendProviderToolResult(request, admission, rejection, ProjectionKind.REJECTION, resultProjection);
                outcomes.add(new Outcome(admission, rejection, OutcomeStatus.REJECTED));
                continue;
            }

            CallExecution execution = delegate.execute(admission);
            if (execution == null || execution.result() == null) {
                execution = CallExecution.completed(ToolResult.failed("Tool execution returned no result."));
            }
            ToolResult result = execution.result();

            if (execution.directive() == ExecutionDirective.LOOP_GUARD_BLOCKED) {
                this.journalResult(request, admission, result);
                this.appendProviderToolResult(request, admission, result, ProjectionKind.LOOP_GUARD, resultProjection);
                Outcome terminalOutcome = new Outcome(admission, result, OutcomeStatus.LOOP_GUARD_BLOCKED);
                outcomes.add(terminalOutcome);
                this.skipRemaining(request, outcomes, index + 1,
                        "Skipped because an earlier tool call in the same model turn was blocked by the loop guard.",
                        resultProjection);
                return BatchResult.terminal(Terminal.LOOP_GUARD, outcomes, terminalOutcome);
            }

            if (isCancelledResult(result) || request.cancellationProbe().isCancellationRequested()) {
                this.journalResult(request, admission, result);
                this.appendProviderToolResult(request, admission, result, ProjectionKind.INTERRUPTED, resultProjection);
                Outcome terminalOutcome = new Outcome(admission, result, OutcomeStatus.INTERRUPTED);
                outcomes.add(terminalOutcome);
                this.interruptRemaining(request, outcomes, index + 1, resultProjection);
                return BatchResult.terminal(Terminal.CANCELLED, outcomes, terminalOutcome);
            }

            if (EnvironmentBlockerClassifier.classify(admission.call().toolName(), result).isPresent()) {
                this.toolCallJournalService.blocked(request.executionFence(), request.taskId(),
                        admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                        request.iteration(), result.getContent());
                this.appendProviderToolResult(request, admission, result, ProjectionKind.ENVIRONMENT_BLOCKED,
                        resultProjection);
                Outcome terminalOutcome = new Outcome(admission, result, OutcomeStatus.ENVIRONMENT_BLOCKED);
                outcomes.add(terminalOutcome);
                this.skipRemaining(request, outcomes, index + 1,
                        "Skipped because an earlier tool call in the same model turn is blocked by the environment.",
                        resultProjection);
                return BatchResult.terminal(Terminal.ENVIRONMENT_BLOCKED, outcomes, terminalOutcome);
            }

            if (result.isApprovalRequired()) {
                this.toolCallJournalService.waitingApproval(request.executionFence(), request.taskId(),
                        admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                        request.iteration(), result.getApprovalId());
                Outcome terminalOutcome = new Outcome(admission, result, OutcomeStatus.WAITING_APPROVAL);
                outcomes.add(terminalOutcome);
                this.skipRemaining(request, outcomes, index + 1,
                        "Skipped because an earlier tool call in the same model turn is waiting for approval.",
                        resultProjection);
                return BatchResult.terminal(Terminal.WAITING_APPROVAL, outcomes, terminalOutcome);
            }

            this.journalResult(request, admission, result);
            if (result.isInteractionRequired()) {
                Outcome terminalOutcome = new Outcome(admission, result, OutcomeStatus.WAITING_USER);
                outcomes.add(terminalOutcome);
                this.skipRemaining(request, outcomes, index + 1,
                        "Skipped because an earlier tool call in the same model turn is waiting for user input.",
                        resultProjection);
                return BatchResult.terminal(Terminal.WAITING_USER, outcomes, terminalOutcome);
            }

            this.appendProviderToolResult(request, admission, result, ProjectionKind.RESULT, resultProjection);
            outcomes.add(new Outcome(admission, result, OutcomeStatus.COMPLETED));
        }
        return BatchResult.continues(outcomes);
    }

    private void skipRemaining(BatchRequest request, List<Outcome> outcomes, int startIndex,
                               String reason, ToolResultProjection resultProjection) {
        for (int index = Math.max(0, startIndex); index < request.admissions().size(); index++) {
            Admission admission = request.admissions().get(index);
            if (!admission.allowed()) {
                ToolResult rejection = admission.rejection();
                this.appendProviderToolResult(request, admission, rejection, ProjectionKind.REJECTION, resultProjection);
                outcomes.add(new Outcome(admission, rejection, OutcomeStatus.REJECTED));
                continue;
            }
            ToolResult skipped = ToolResult.failed(reason);
            this.toolCallJournalService.skipped(request.executionFence(), request.taskId(),
                    admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                    request.iteration(), reason);
            this.appendProviderToolResult(request, admission, skipped, ProjectionKind.SKIPPED, resultProjection);
            outcomes.add(new Outcome(admission, skipped, OutcomeStatus.SKIPPED));
        }
    }

    private void interruptRemaining(BatchRequest request, List<Outcome> outcomes, int startIndex,
                                    ToolResultProjection resultProjection) {
        for (int index = Math.max(0, startIndex); index < request.admissions().size(); index++) {
            Admission admission = request.admissions().get(index);
            ToolResult interrupted = ToolResult.failed(CANCELLATION_DETAIL);
            if (admission.allowed()) {
                this.toolCallJournalService.interrupted(request.executionFence(), request.taskId(),
                        admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                        request.iteration(), CANCELLATION_DETAIL);
            }
            this.appendProviderToolResult(request, admission, interrupted, ProjectionKind.INTERRUPTED,
                    resultProjection);
            outcomes.add(new Outcome(admission, interrupted, OutcomeStatus.INTERRUPTED));
        }
    }

    private void journalResult(BatchRequest request, Admission admission, ToolResult result) {
        String detail = result == null || result.getContent() == null ? "" : result.getContent();
        if (result != null && result.isInteractionRequired()) {
            this.toolCallJournalService.waitingInteraction(request.executionFence(), request.taskId(),
                    admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                    request.iteration(), result.getInteractionRequestId(), result.getInteractionType(), detail,
                    result.getInteractionPayload());
        } else if (result != null && result.isSuccess()) {
            this.toolCallJournalService.completed(request.executionFence(), request.taskId(),
                    admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                    request.iteration(), result);
        } else if (isCancelledResult(result)) {
            this.toolCallJournalService.interrupted(request.executionFence(), request.taskId(),
                    admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                    request.iteration(), detail);
        } else if (result != null && "infrastructure_error".equals(result.getExecutionStatus())) {
            this.toolCallJournalService.blocked(request.executionFence(), request.taskId(),
                    admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                    request.iteration(), detail);
        } else {
            this.toolCallJournalService.failed(request.executionFence(), request.taskId(),
                    admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                    request.iteration(), result);
        }
    }

    private void appendProviderToolResult(BatchRequest request, Admission admission, ToolResult result,
                                          ProjectionKind kind, ToolResultProjection resultProjection) {
        String projection = resultProjection.project(admission, result, kind);
        this.transcriptAppender.append(request.executionFence(), request.taskId(), request.transcriptEpoch(),
                this.protocol.toolResultMessage(admission.call(), "[Tool " + admission.call().toolName()
                        + " result]\n" + (projection == null ? "" : projection)));
    }

    private static boolean isCancelledResult(ToolResult result) {
        return result != null && "cancelled".equals(result.getExecutionStatus());
    }

    @FunctionalInterface
    public interface ToolExecutionDelegate {
        CallExecution execute(Admission admission) throws Exception;
    }

    @FunctionalInterface
    public interface ToolResultProjection {
        String project(Admission admission, ToolResult result, ProjectionKind kind);
    }

    @FunctionalInterface
    public interface CancellationProbe {
        boolean isCancellationRequested();
    }

    public enum ExecutionDirective {
        CONTINUE,
        LOOP_GUARD_BLOCKED
    }

    public enum Terminal {
        CONTINUE,
        LOOP_GUARD,
        ENVIRONMENT_BLOCKED,
        WAITING_APPROVAL,
        WAITING_USER,
        CANCELLED
    }

    public enum OutcomeStatus {
        REJECTED,
        COMPLETED,
        LOOP_GUARD_BLOCKED,
        ENVIRONMENT_BLOCKED,
        WAITING_APPROVAL,
        WAITING_USER,
        SKIPPED,
        INTERRUPTED
    }

    public enum ProjectionKind {
        REJECTION,
        RESULT,
        LOOP_GUARD,
        ENVIRONMENT_BLOCKED,
        SKIPPED,
        INTERRUPTED
    }

    public record BatchRequest(ExecutionFence executionFence, Long taskId, long transcriptEpoch, int iteration,
                               String assistantContent, List<Admission> admissions,
                               CancellationProbe cancellationProbe) {
        public BatchRequest {
            assistantContent = assistantContent == null ? "" : assistantContent;
            admissions = admissions == null ? List.of() : List.copyOf(admissions);
            cancellationProbe = cancellationProbe == null ? () -> false : cancellationProbe;
        }

        private void validate() {
            Objects.requireNonNull(executionFence, "executionFence is required");
            if (taskId == null || taskId <= 0) {
                throw new IllegalArgumentException("taskId is required");
            }
            if (admissions.isEmpty()) {
                throw new IllegalArgumentException("native tool batch must contain at least one admission");
            }
        }
    }

    public record Admission(AgentModelTurnExecutor.NativeToolCall call,
                            AgentToolTurnExecutor.ToolInputResolution input,
                            JsonObject publicArguments) {
        public Admission {
            call = Objects.requireNonNull(call, "call is required");
            publicArguments = publicArguments == null ? new JsonObject() : publicArguments.deepCopy();
        }

        public boolean allowed() {
            return input != null && input.allowed();
        }

        public JsonObject arguments() {
            return input == null ? new JsonObject() : input.arguments();
        }

        public ToolResult rejection() {
            return input == null ? ToolResult.failed("Tool input was rejected.") : input.rejection();
        }

        public String reasonCode() {
            return input == null ? "unknown" : input.reasonCode();
        }
    }

    public record CallExecution(ToolResult result, ExecutionDirective directive) {
        public CallExecution {
            directive = directive == null ? ExecutionDirective.CONTINUE : directive;
        }

        public static CallExecution completed(ToolResult result) {
            return new CallExecution(result, ExecutionDirective.CONTINUE);
        }

        public static CallExecution loopGuardBlocked(ToolResult result) {
            return new CallExecution(result, ExecutionDirective.LOOP_GUARD_BLOCKED);
        }
    }

    public record Outcome(Admission admission, ToolResult result, OutcomeStatus status) {
    }

    public record BatchResult(Terminal terminal, List<Outcome> outcomes, Outcome terminalOutcome) {
        public BatchResult {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }

        private static BatchResult continues(List<Outcome> outcomes) {
            return new BatchResult(Terminal.CONTINUE, outcomes, null);
        }

        private static BatchResult terminal(Terminal terminal, List<Outcome> outcomes, Outcome terminalOutcome) {
            return new BatchResult(terminal, outcomes, terminalOutcome);
        }
    }
}
