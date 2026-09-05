package com.labex.labexagent.runtime;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentSubagentProperties;
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
 * <p>一个模型 turn 的所有 tool call 必须先写入 assistant transcript 与 Tool Part，
 * 随后按 OpenCode / AI SDK 的并发工具调度模型执行：同一批全部 allowed 工具并发派发
 * （互不等待，多个 task 子代理因此天然并行），但 durable 结果仍按 Provider 返回顺序
 * 串行投影，保证 transcript 顺序、Part 状态机与审批/交互语义不变。</p>
 *
 * <p>线程隔离（防"等待型工具饿死普通工具"）：task / subagent 等会长时间阻塞等待
 * 子代理终态的调用进入独立 {@code wait} 池（线程数 = max-parallel）；read/write/
 * bash 等普通工具进入 {@code batch} 池（线程数 = max(max-parallel × 2, 8)）。
 * 主代理一次派发 N 个前台子代理时，等待不挤占普通工具线程，子代理 runLoop 内的
 * 工具调用不再排队。</p>
 *
 * <p>终态语义（对齐既有不变式）：按顺序消费每个工具的结果；遇到第一个终态指令
 * （等待审批、用户交互、环境阻断、循环阻断或取消）即终止本批：仍在运行的工具被
 * interrupt 并显式标记为 skipped / interrupted，已经跑完但尚未消费的工具则按真实
 * 结果收尾——任何 Tool Part 都不会滞留 pending，模型、浏览器与恢复逻辑永远看到
 * 明确终态。与旧串行实现的唯一行为差异：终态判定点之后的工具可能在并发窗口内已
 * 经完成，其副作用真实发生并如实入账（这正是 OpenCode 并行工具调用的语义）。</p>
 *
 * <p>具体工具执行、循环判定、SSE 投影和生命周期迁移仍由上层调用方拥有；本组件只收敛
 * structured batch 的 durable 协议、并发派发、顺序投影与 Part 状态机。</p>
 */
@Service
public class LabexNativeToolBatchExecutor {
    private static final String CANCELLATION_DETAIL =
            "Interrupted because the run was cancelled before this tool call started.";

    /** 普通工具并发上限（守护线程、空闲回收）。 */
    private static final int MAX_PARALLEL_TOOLS = 16;
    /** 等待型工具（task/subagent）等待线程池上限，与子代理并发上限对齐。 */
    private static final int MAX_PARALLEL_WAIT_TOOLS = 16;

    private final AgentToolCallBatchProtocol protocol;
    private final AgentProviderTranscriptAppender transcriptAppender;
    private final AgentToolCallJournalService toolCallJournalService;
    private final AgentSubagentProperties subagentProperties;
    /** 普通工具线程池：并行上限 = max(max-parallel × 2, 8)，只跑非等待型工具。 */
    private final java.util.concurrent.ExecutorService toolPool;
    /** 等待型工具线程池：并行上限 = max-parallel，只跑 task/subagent 等长阻塞调用。 */
    private final java.util.concurrent.ExecutorService waitPool;
    private final java.util.concurrent.atomic.AtomicInteger poolSequence = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger waitSequence = new java.util.concurrent.atomic.AtomicInteger();

    public LabexNativeToolBatchExecutor(AgentToolCallBatchProtocol protocol,
                                        AgentProviderTranscriptAppender transcriptAppender,
                                        AgentToolCallJournalService toolCallJournalService) {
        this(protocol, transcriptAppender, toolCallJournalService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public LabexNativeToolBatchExecutor(AgentToolCallBatchProtocol protocol,
                                        AgentProviderTranscriptAppender transcriptAppender,
                                        AgentToolCallJournalService toolCallJournalService,
                                        AgentSubagentProperties subagentProperties) {
        this.protocol = Objects.requireNonNull(protocol, "protocol is required");
        this.transcriptAppender = Objects.requireNonNull(transcriptAppender, "transcriptAppender is required");
        this.toolCallJournalService = Objects.requireNonNull(toolCallJournalService,
                "toolCallJournalService is required");
        this.subagentProperties = subagentProperties == null ? new AgentSubagentProperties() : subagentProperties;
        // 普通工具池：给一次批派发留出明显余量（≥ 2×并行上限，至少 8 线程），
        // 保证多个 runLoop（主代理 + 子代理）的普通工具调用互不排队。
        int parallel = Math.max(8, Math.min(MAX_PARALLEL_TOOLS, this.subagentProperties.getMaxParallel() * 2));
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                parallel, parallel, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(512),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "labex-agent-tool-batch-" + poolSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        this.toolPool = pool;
        // 等待型工具池：线程数 = max-parallel（子代理并发上限天然约束同时等待数量），
        // 与普通工具池隔离，task 的前台等待不再挤占 read/write/bash 等工具的执行线程。
        int waitThreads = Math.max(1, Math.min(MAX_PARALLEL_WAIT_TOOLS, this.subagentProperties.getMaxParallel()));
        java.util.concurrent.ThreadPoolExecutor waitExecutor = new java.util.concurrent.ThreadPoolExecutor(
                waitThreads, waitThreads, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(512),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "labex-agent-tool-wait-" + waitSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        waitExecutor.allowCoreThreadTimeOut(true);
        this.waitPool = waitExecutor;
    }

    /**
     * 执行一个已经完成参数/schema admission 的 native tool batch。
     *
     * <p>并发派发全部 allowed 工具（对齐 OpenCode：同一 turn 的多个 tool call 并行执行，
     * task 子代理互不串行等待），再按 Provider 顺序消费结果：顺序投影与终态语义见类注释。
     * 调用方提供的 delegate 是唯一的实际工具执行入口，因此文件快照、权限、post-edit hook、
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
        boolean cancelledBeforeDispatch = request.cancellationProbe().isCancellationRequested();

        // ---------- 阶段 2：并发派发 ----------
        // 每个 allowed 工具在独立线程上立即开始执行；未派发位置留 null 占位，保持 index 对齐。
        // 等待型工具（task/subagent）进入独立的 wait 池，不占用普通工具线程：
        // 主代理一次派发 N 个子代理时，子代理 runLoop 内的普通工具调用仍然畅通。
        List<java.util.concurrent.Future<CallExecution>> inFlight = new ArrayList<>(request.admissions().size());
        for (Admission admission : request.admissions()) {
            if (!admission.allowed() || cancelledBeforeDispatch) {
                inFlight.add(null);
                continue;
            }
            java.util.concurrent.ExecutorService targetPool =
                    ToolExecutionBudget.isBlockingWaitTool(admission.call().toolName()) ? this.waitPool : this.toolPool;
            inFlight.add(targetPool.submit(() -> invokeDelegate(delegate, admission)));
        }
        if (cancelledBeforeDispatch) {
            this.settleRemaining(request, outcomes, 0, inFlight, true, CANCELLATION_DETAIL, resultProjection);
            return BatchResult.terminal(Terminal.CANCELLED, outcomes, null);
        }

        // ---------- 阶段 3：按 Provider 顺序消费结果 ----------
        for (int index = 0; index < request.admissions().size(); index++) {
            Admission admission = request.admissions().get(index);
            if (request.cancellationProbe().isCancellationRequested()) {
                this.settleRemaining(request, outcomes, index, inFlight, true, CANCELLATION_DETAIL,
                        resultProjection);
                return BatchResult.terminal(Terminal.CANCELLED, outcomes, null);
            }

            if (!admission.allowed()) {
                ToolResult rejection = admission.rejection();
                this.appendProviderToolResult(request, admission, rejection, ProjectionKind.REJECTION,
                        resultProjection);
                outcomes.add(new Outcome(admission, rejection, OutcomeStatus.REJECTED));
                continue;
            }

            CallExecution execution = this.awaitExecution(request, outcomes, index, inFlight, resultProjection);
            if (execution == null) {
                // 并发窗口内被取消/中断的调用已由 awaitExecution 结算并产出 interrupted outcome。
                continue;
            }
            if (execution.result() == null) {
                execution = CallExecution.completed(ToolResult.failed("Tool execution returned no result."));
            }
            ToolResult result = execution.result();

            if (execution.directive() == ExecutionDirective.LOOP_GUARD_BLOCKED) {
                this.journalResult(request, admission, result);
                this.appendProviderToolResult(request, admission, result, ProjectionKind.LOOP_GUARD,
                        resultProjection);
                Outcome terminalOutcome = new Outcome(admission, result, OutcomeStatus.LOOP_GUARD_BLOCKED);
                outcomes.add(terminalOutcome);
                this.settleRemaining(request, outcomes, index + 1, inFlight, false,
                        "Skipped because an earlier tool call in the same model turn was blocked by the loop guard.",
                        resultProjection);
                return BatchResult.terminal(Terminal.LOOP_GUARD, outcomes, terminalOutcome);
            }

            if (isCancelledResult(result) || request.cancellationProbe().isCancellationRequested()) {
                this.journalResult(request, admission, result);
                this.appendProviderToolResult(request, admission, result, ProjectionKind.INTERRUPTED,
                        resultProjection);
                Outcome terminalOutcome = new Outcome(admission, result, OutcomeStatus.INTERRUPTED);
                outcomes.add(terminalOutcome);
                this.settleRemaining(request, outcomes, index + 1, inFlight, true, CANCELLATION_DETAIL,
                        resultProjection);
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
                this.settleRemaining(request, outcomes, index + 1, inFlight, false,
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
                this.settleRemaining(request, outcomes, index + 1, inFlight, false,
                        "Skipped because an earlier tool call in the same model turn is waiting for approval.",
                        resultProjection);
                return BatchResult.terminal(Terminal.WAITING_APPROVAL, outcomes, terminalOutcome);
            }

            this.journalResult(request, admission, result);
            if (result.isInteractionRequired()) {
                Outcome terminalOutcome = new Outcome(admission, result, OutcomeStatus.WAITING_USER);
                outcomes.add(terminalOutcome);
                this.settleRemaining(request, outcomes, index + 1, inFlight, false,
                        "Skipped because an earlier tool call in the same model turn is waiting for user input.",
                        resultProjection);
                return BatchResult.terminal(Terminal.WAITING_USER, outcomes, terminalOutcome);
            }

            this.appendProviderToolResult(request, admission, result, ProjectionKind.RESULT, resultProjection);
            outcomes.add(new Outcome(admission, result, OutcomeStatus.COMPLETED));
        }
        return BatchResult.continues(outcomes);
    }

    /** 在独立线程上执行委托；抛出的异常原样保留供 awaitExecution 重抛。 */
    private CallExecution invokeDelegate(ToolExecutionDelegate delegate, Admission admission) {
        try {
            return delegate.execute(admission);
        } catch (Exception failure) {
            throw new DelegateExecutionException(failure);
        }
    }

    /**
     * 按序等待第 index 个工具的结果；若该调用已在并发窗口内被取消（终态判定之后仍
     * 在跑的调用被 interrupt），则结算为 interrupted outcome 并返回 null，消费方跳过。
     */
    private CallExecution awaitExecution(BatchRequest request, List<Outcome> outcomes, int index,
                                         List<java.util.concurrent.Future<CallExecution>> inFlight,
                                         ToolResultProjection resultProjection) throws Exception {
        java.util.concurrent.Future<CallExecution> future = inFlight.get(index);
        if (future == null) {
            return null;
        }
        try {
            CallExecution execution = future.get();
            return execution == null ? CallExecution.completed(
                    ToolResult.failed("Tool execution returned no result.")) : execution;
        } catch (java.util.concurrent.CancellationException cancelled) {
            // 本批后续终态已中断该调用：由消费方 settleRemaining 结算，这里只占位 outcome。
            ToolResult interrupted = ToolResult.failed(CANCELLATION_DETAIL);
            outcomes.add(new Outcome(request.admissions().get(index), interrupted, OutcomeStatus.INTERRUPTED));
            return null;
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            // 与旧串行语义一致：delegate 异常向上传播；尚在运行的其余调用先结算为 interrupted，
            // 避免 Tool Part 滞留 pending（比依赖 reconciler 事后回收更早闭环）。
            this.settleRemaining(request, outcomes, index + 1, inFlight, true, CANCELLATION_DETAIL,
                    resultProjection);
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    /**
     * 终态之后的剩余结算：并发窗口内已完成的调用按真实结果入账（不伪造成 skipped，
     * 其副作用真实发生过），尚未完成的一律 interrupt 并显式标记 skipped / interrupted，
     * 已拒绝（输入门禁）的调用补齐 REJECTION 投影。保证没有任何 Tool Part 滞留 pending。
     */
    private void settleRemaining(BatchRequest request, List<Outcome> outcomes, int startIndex,
                                 List<java.util.concurrent.Future<CallExecution>> inFlight,
                                 boolean interrupted, String reason,
                                 ToolResultProjection resultProjection) throws Exception {
        for (int index = Math.max(0, startIndex); index < request.admissions().size(); index++) {
            Admission admission = request.admissions().get(index);
            if (!admission.allowed()) {
                ToolResult rejection = admission.rejection();
                this.appendProviderToolResult(request, admission, rejection, ProjectionKind.REJECTION,
                        resultProjection);
                outcomes.add(new Outcome(admission, rejection, OutcomeStatus.REJECTED));
                continue;
            }
            java.util.concurrent.Future<CallExecution> future = inFlight.get(index);
            if (future == null) {
                continue;
            }
            if (future.isDone() && !future.isCancelled()) {
                // 已在并发窗口内完成：以真实结果收尾，绝不伪造 skipped。
                try {
                    CallExecution execution = future.get();
                    ToolResult real = execution == null || execution.result() == null
                            ? ToolResult.failed("Tool execution returned no result.") : execution.result();
                    this.appendProviderToolResult(request, admission, real, ProjectionKind.RESULT,
                            resultProjection);
                    outcomes.add(new Outcome(admission, real, OutcomeStatus.COMPLETED));
                } catch (java.util.concurrent.ExecutionException failure) {
                    ToolResult real = ToolResult.failed(
                            "Tool execution failed while the batch was settling: "
                                    + (failure.getCause() == null ? "unknown" : failure.getCause().getMessage()));
                    this.appendProviderToolResult(request, admission, real, ProjectionKind.RESULT,
                            resultProjection);
                    outcomes.add(new Outcome(admission, real, OutcomeStatus.COMPLETED));
                } catch (InterruptedException interruptedWait) {
                    Thread.currentThread().interrupt();
                    ToolResult real = ToolResult.failed("Tool execution was interrupted while the batch settled.");
                    this.appendProviderToolResult(request, admission, real, ProjectionKind.INTERRUPTED,
                            resultProjection);
                    outcomes.add(new Outcome(admission, real, OutcomeStatus.INTERRUPTED));
                }
                continue;
            }
            // 尚未完成：interrupt 并显式标记终态。
            future.cancel(true);
            ToolResult settled = ToolResult.failed(reason);
            if (interrupted) {
                this.toolCallJournalService.interrupted(request.executionFence(), request.taskId(),
                        admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                        request.iteration(), reason);
                this.appendProviderToolResult(request, admission, settled, ProjectionKind.INTERRUPTED,
                        resultProjection);
                outcomes.add(new Outcome(admission, settled, OutcomeStatus.INTERRUPTED));
            } else {
                this.toolCallJournalService.skipped(request.executionFence(), request.taskId(),
                        admission.call().toolCallId(), admission.call().toolName(), admission.publicArguments(),
                        request.iteration(), reason);
                this.appendProviderToolResult(request, admission, settled, ProjectionKind.SKIPPED,
                        resultProjection);
                outcomes.add(new Outcome(admission, settled, OutcomeStatus.SKIPPED));
            }
        }
    }

    /** 包装 delegate 异常，避免被线程池吞成裸 RuntimeException。 */
    private static final class DelegateExecutionException extends RuntimeException {
        private DelegateExecutionException(Exception cause) {
            super(cause);
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
