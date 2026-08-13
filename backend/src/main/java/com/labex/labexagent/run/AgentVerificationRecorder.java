package com.labex.labexagent.run;

import com.labex.entity.AgentVerification;
import com.labex.labexagent.commandsecurity.CommandRedactor;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.tool.ToolResult;
import com.labex.mapper.AgentVerificationMapper;
import java.time.LocalDateTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 持久化 Agent 验证结果的唯一入口，保证直接 run_tests 和审批后执行共用同一证据契约。
 *
 * <p>执行器发起的写入（fenced overload）在 INSERT 前验证 {@link ExecutionFence}，
 * stale fence 抛出 typed failure 且零写入；控制面（审批后重放）继续使用 legacy overload。</p>
 */
@Service
public class AgentVerificationRecorder {
    private static final Logger log = LoggerFactory.getLogger(AgentVerificationRecorder.class);

    private final AgentVerificationMapper verificationMapper;
    private final AgentRunArtifactService artifactService;
    private AgentRunExecutionLeaseService leaseService;

    public AgentVerificationRecorder(AgentVerificationMapper verificationMapper,
                                     AgentRunArtifactService artifactService) {
        this.verificationMapper = verificationMapper;
        this.artifactService = artifactService;
        this.leaseService = null;
    }

    /**
     * 可选注入 lease authority：完整应用上下文始终提供该 bean；测试切片或降级上下文缺省时，
     * fenced overload 以 IllegalStateException fail closed（不静默退化为无 fence 写入）。
     */
    @Autowired(required = false)
    public void setExecutionLeaseService(AgentRunExecutionLeaseService leaseService) {
        this.leaseService = leaseService;
    }

    public boolean recordProcessResult(Long taskId, Integer studentId, Integer projectId,
                                       String command, String strategy, ProcessExecutionResult result) {
        if (result == null) return false;
        ToolResult toolResult = ToolResult.fromProcessExecution(result);
        return record(null, taskId, studentId, projectId, command, strategy, toolResult.getExecutionStatus(),
                result.exitCode(), toolResult.getContent());
    }

    /**
     * Executor-fenced 进程结果写入：先验证 {@link ExecutionFence}，stale fence 抛出 typed failure 且零写入。
     * 前提：执行器调用点总是传入非 null fence；null 仅为控制面调用方容忍（soft gate），
     * 与 PartService 的 hard gate 语义一致的前提是执行器路径永不传 null。
     */
    public boolean recordProcessResult(ExecutionFence fence, Long taskId, Integer studentId, Integer projectId,
                                       String command, String strategy, ProcessExecutionResult result) {
        if (result == null) return false;
        ToolResult toolResult = ToolResult.fromProcessExecution(result);
        return record(fence, taskId, studentId, projectId, command, strategy, toolResult.getExecutionStatus(),
                result.exitCode(), toolResult.getContent());
    }

    public boolean recordToolResult(Long taskId, Integer studentId, Integer projectId,
                                    String command, String strategy, ToolResult result) {
        if (result == null) return false;
        return record(null, taskId, studentId, projectId, command, strategy, result.getExecutionStatus(),
                result.isSuccess() ? 0 : 1, result.getContent());
    }

    /**
     * Executor-fenced 工具结果写入：先验证 {@link ExecutionFence}，stale fence 抛出 typed failure 且零写入。
     * 前提：执行器调用点总是传入非 null fence；null 仅为控制面调用方容忍（soft gate）。
     */
    public boolean recordToolResult(ExecutionFence fence, Long taskId, Integer studentId, Integer projectId,
                                    String command, String strategy, ToolResult result) {
        if (result == null) return false;
        return record(fence, taskId, studentId, projectId, command, strategy, result.getExecutionStatus(),
                result.isSuccess() ? 0 : 1, result.getContent());
    }

    private boolean record(ExecutionFence fence, Long taskId, Integer studentId, Integer projectId,
                           String command, String strategy, String executionStatus,
                           Integer exitCode, String output) {
        if (fence != null) {
            requireFence(fence);
        }
        if (taskId == null || studentId == null || projectId == null
                || verificationMapper == null || artifactService == null) {
            return false;
        }
        String safeCommand = CommandRedactor.redact(command == null ? "" : command);
        String safeOutput = CommandRedactor.redact(output == null ? "" : output);
        String normalizedStrategy = strategy == null || strategy.isBlank()
                ? "auto" : strategy.trim().toLowerCase(Locale.ROOT);
        try {
            AgentVerification verification = new AgentVerification();
            verification.setTaskId(taskId);
            verification.setChangeSetId(0L);
            verification.setStudentId(studentId);
            verification.setProjectId(projectId);
            verification.setCommand(safeCommand);
            verification.setStatus(verificationStatus(executionStatus, exitCode));
            verification.setExitCode(exitCode);
            verification.setOutput(safeOutput);
            verification.setCreateTime(LocalDateTime.now());
            verificationMapper.insert(verification);
            if (fence == null) {
                artifactService.record(taskId, "verification_log", null,
                        "strategy=" + normalizedStrategy + "\n" + safeOutput);
            } else {
                artifactService.record(fence, taskId, "verification_log", null,
                        "strategy=" + normalizedStrategy + "\n" + safeOutput);
            }
            return true;
        } catch (AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {
            // 验证行已持久化但 artifact 投影因 fence 失效被拒：验证行本身是主要事实，typed failure 仍向上暴露。
            throw staleFence;
        } catch (RuntimeException failure) {
            log.warn("AGENT_VERIFICATION_PERSIST_FAILED taskId={} projectId={} strategy={} errorType={}",
                    taskId, projectId, normalizedStrategy, failure.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 验证状态词汇：只有真实 exit code 0（且执行状态 SUCCEEDED）才能计入 passed；
     * timed_out / cancelled / infrastructure_error 单独记录，供完成证据区分环境受阻与代码失败。
     */
    private String verificationStatus(String executionStatus, Integer exitCode) {
        boolean exitedZero = Integer.valueOf(0).equals(exitCode);
        if (executionStatus == null || executionStatus.isBlank()) {
            return exitedZero ? "passed" : "failed";
        }
        return switch (executionStatus.toLowerCase(Locale.ROOT)) {
            case "succeeded" -> exitedZero ? "passed" : "failed";
            case "timed_out" -> "timed_out";
            case "cancelled" -> "cancelled";
            case "infrastructure_error" -> "infrastructure_error";
            default -> exitedZero ? "passed" : "failed";
        };
    }

    private void requireFence(ExecutionFence fence) {
        if (leaseService == null) {
            throw new IllegalStateException("ExecutionFence support is not configured");
        }
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for executor-originated writes");
        }
        leaseService.requireActiveFence(fence, LocalDateTime.now());
    }
}
