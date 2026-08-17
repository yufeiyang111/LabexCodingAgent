package com.labex.labexagent.runtime;

import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.run.RunCompletionEvidence;
import com.labex.labexagent.run.RunCompletionEvidenceService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将“已完成真实 workspace 改动且具备验证证据”投影成一次可恢复的收束提示。
 *
 * <p>它不会根据关键字或模型思考过程强制停止工具调用。只有服务器拥有的完成证据已满足、
 * 并且 task 确实包含 workspace 改动时，才向下一轮模型请求追加一个 durable 提醒：若不能指出
 * 具体未满足的用户要求，应直接给出最终答复。消息与事件在同一事务内持久化，重启或重连不会丢失提示，
 * 也不会因为重复恢复重复追加。</p>
 */
@Service
public class AgentCompletionReadinessService {
    private static final String READY_REASON = "verified_workspace_change";
    private static final String DIRECTIVE = """
            [Runtime completion readiness]
            Server-owned workspace change and verification evidence is sufficient for a final response.
            Before making another tool call, identify a concrete unmet user requirement that is not covered by the recorded evidence.
            If no such requirement exists, provide the final response now. Do not keep exploring, installing, or rerunning checks merely as ceremony.
            """;

    private final RunCompletionEvidenceService evidenceService;
    private final AgentRunTranscriptService transcriptService;
    private final AgentRunLifecycleService lifecycleService;

    public AgentCompletionReadinessService(RunCompletionEvidenceService evidenceService,
                                           AgentRunTranscriptService transcriptService,
                                           AgentRunLifecycleService lifecycleService) {
        this.evidenceService = Objects.requireNonNull(evidenceService, "evidenceService is required");
        this.transcriptService = Objects.requireNonNull(transcriptService, "transcriptService is required");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService is required");
    }

    /**
     * 在同一事务中写入 transcript 指令与事件。只有两者都可持久化时，才向调用方返回可投影的事件。
     */
    @Transactional(rollbackFor = Exception.class)
    public Signal signalIfReady(ExecutionFence fence, Long taskId, long executionEpoch,
                                Integer studentId, Integer projectId, boolean manualFileVerification) {
        RunCompletionEvidence evidence = evidenceService.evaluateAndPersist(
                fence, taskId, studentId, projectId, manualFileVerification, "running");
        if (evidence == null) {
            return Signal.notReady("completion_evidence_unavailable", "");
        }
        if (!evidence.satisfied()) {
            return Signal.notReady("completion_evidence_unsatisfied", CompletionEvidenceFingerprint.of(evidence));
        }
        if (evidence.changedFiles().isEmpty()) {
            // 只读/分析任务仍由模型自行根据用户语义决定何时结束，不能被编码任务的验证事实抢占。
            return Signal.notReady("no_workspace_change", CompletionEvidenceFingerprint.of(evidence));
        }

        String fingerprint = CompletionEvidenceFingerprint.of(evidence);
        boolean appended = transcriptService.appendCompletionReadinessDirective(
                fence, taskId, executionEpoch, fingerprint, DIRECTIVE);
        if (!appended) {
            return Signal.alreadySignaled(fingerprint);
        }

        Map<String, Object> payload = readinessPayload(taskId, executionEpoch, fingerprint, evidence);
        AgentRunEvent event = lifecycleService.appendEvent(fence, taskId, "COMPLETION_READY", payload,
                "completion-ready-" + taskId + "-" + executionEpoch + "-" + fingerprint);
        return Signal.signaled(fingerprint, evidence, event, payload, DIRECTIVE);
    }

    private Map<String, Object> readinessPayload(Long taskId, long executionEpoch, String fingerprint,
                                                  RunCompletionEvidence evidence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("executionEpoch", executionEpoch);
        payload.put("evidenceFingerprint", fingerprint);
        payload.put("reasonCode", READY_REASON);
        payload.put("satisfied", true);
        payload.put("changedFiles", evidence.changedFiles());
        payload.put("successfulVerifications", evidence.successfulVerifications());
        return Map.copyOf(payload);
    }

    public enum SignalStatus {
        SIGNALED,
        ALREADY_SIGNALED,
        NOT_READY
    }

    public record Signal(SignalStatus status, String reasonCode, String evidenceFingerprint,
                         RunCompletionEvidence evidence, AgentRunEvent event,
                         Map<String, Object> payload, String directive) {
        public Signal {
            status = status == null ? SignalStatus.NOT_READY : status;
            reasonCode = reasonCode == null ? "" : reasonCode;
            evidenceFingerprint = evidenceFingerprint == null ? "" : evidenceFingerprint;
            payload = payload == null ? Map.of() : Map.copyOf(payload);
            directive = directive == null ? "" : directive;
        }

        static Signal signaled(String fingerprint, RunCompletionEvidence evidence, AgentRunEvent event,
                               Map<String, Object> payload, String directive) {
            return new Signal(SignalStatus.SIGNALED, READY_REASON, fingerprint, evidence, event, payload, directive);
        }

        static Signal alreadySignaled(String fingerprint) {
            return new Signal(SignalStatus.ALREADY_SIGNALED, READY_REASON, fingerprint, null, null, Map.of(), "");
        }

        static Signal notReady(String reasonCode, String fingerprint) {
            return new Signal(SignalStatus.NOT_READY, reasonCode, fingerprint, null, null, Map.of(), "");
        }

        public boolean signaled() {
            return status == SignalStatus.SIGNALED;
        }
    }
}
