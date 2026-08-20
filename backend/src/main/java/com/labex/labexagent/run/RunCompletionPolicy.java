package com.labex.labexagent.run;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 仅基于服务器拥有的改动、验证、工具执行事实和运行状态判定能否宣告完成。 */
public final class RunCompletionPolicy {
    public RunCompletionEvidence evaluate(Input input) {
        Input safe = input == null
                ? new Input(null, List.of(), List.of(), List.of(), List.of(), List.of(), false, "unknown", List.of(),
                new PreviewEvidence(PreviewEvidence.Status.NOT_REQUESTED, "", "", ""))
                : input;
        List<String> risks = new ArrayList<>(safe.unresolvedRisks());
        String state = safe.runState() == null ? "unknown" : safe.runState().toLowerCase(Locale.ROOT);
        boolean terminalBlocker = "cancelled".equals(state) || "waiting_environment".equals(state)
                || "failed".equals(state) || "cancelling".equals(state);
        if (terminalBlocker) risks.add("运行状态不允许宣告完成：" + state);
        if (!safe.failedVerifications().isEmpty()) risks.add("存在失败的验证记录");
        if (!safe.environmentVerifications().isEmpty()) {
            risks.add("存在环境受阻的验证（超时/取消/基础设施故障），不能计入通过："
                    + String.join("; ", safe.environmentVerifications()));
        }
        if (!safe.unresolvedToolFailures().isEmpty()) {
            risks.add("存在尚未由同一实际操作成功重试消解的工具执行失败："
                    + String.join("; ", safe.unresolvedToolFailures()));
        }
        boolean hasChanges = !safe.changedFiles().isEmpty();
        boolean verified = !safe.successfulVerifications().isEmpty() || safe.manualFileVerification();
        if (hasChanges && !verified) risks.add("存在文件改动，但没有成功验证证据");

        List<RunCompletionEvidence.Criterion> criteria = List.of(
                new RunCompletionEvidence.Criterion("run_state", "运行状态允许完成", !terminalBlocker, state),
                new RunCompletionEvidence.Criterion("verification_failures", "没有代码验证失败", safe.failedVerifications().isEmpty(),
                        safe.failedVerifications().isEmpty() ? "none" : String.join(", ", safe.failedVerifications())),
                new RunCompletionEvidence.Criterion("environment_verifications", "环境验证可用（没有超时/取消/基础设施受阻）",
                        safe.environmentVerifications().isEmpty(),
                        safe.environmentVerifications().isEmpty() ? "none" : String.join(", ", safe.environmentVerifications())),
                new RunCompletionEvidence.Criterion("tool_execution_failures", "没有未解决的工具执行失败",
                        safe.unresolvedToolFailures().isEmpty(),
                        safe.unresolvedToolFailures().isEmpty() ? "none" : String.join(", ", safe.unresolvedToolFailures())),
                new RunCompletionEvidence.Criterion("unresolved_risks", "没有未解决风险", safe.unresolvedRisks().isEmpty(),
                        safe.unresolvedRisks().isEmpty() ? "none" : String.join(", ", safe.unresolvedRisks())),
                new RunCompletionEvidence.Criterion("preview_fact", "预览状态已持久化", true,
                        safe.preview().status().name().toLowerCase(Locale.ROOT)),
                new RunCompletionEvidence.Criterion("changed_files_verified", "改动已验证", !hasChanges || verified,
                        hasChanges ? (verified ? "verified" : "missing verification") : "informational task"));
        return new RunCompletionEvidence(safe.taskId(), safe.changedFiles(), safe.successfulVerifications(),
                safe.failedVerifications(), safe.environmentVerifications(), safe.unresolvedToolFailures(), risks,
                criteria, risks.isEmpty(), safe.preview(), LocalDateTime.now());
    }

    public record Input(Long taskId, List<String> changedFiles, List<String> successfulVerifications,
                        List<String> failedVerifications, List<String> environmentVerifications,
                        List<String> unresolvedToolFailures, boolean manualFileVerification, String runState,
                        List<String> unresolvedRisks, PreviewEvidence preview) {
        public Input(Long taskId, List<String> changedFiles, List<String> successfulVerifications,
                     List<String> failedVerifications, List<String> environmentVerifications,
                     boolean manualFileVerification, String runState, List<String> unresolvedRisks,
                     PreviewEvidence preview) {
            this(taskId, changedFiles, successfulVerifications, failedVerifications, environmentVerifications,
                    List.of(), manualFileVerification, runState, unresolvedRisks, preview);
        }

        public Input(Long taskId, List<String> changedFiles, List<String> successfulVerifications,
                     List<String> failedVerifications, List<String> environmentVerifications,
                     boolean manualFileVerification, String runState, List<String> unresolvedRisks) {
            this(taskId, changedFiles, successfulVerifications, failedVerifications, environmentVerifications,
                    List.of(), manualFileVerification, runState, unresolvedRisks,
                    new PreviewEvidence(PreviewEvidence.Status.NOT_REQUESTED, "", "", ""));
        }

        public Input(Long taskId, List<String> changedFiles, List<String> successfulVerifications,
                     List<String> failedVerifications, boolean manualFileVerification, String runState) {
            this(taskId, changedFiles, successfulVerifications, failedVerifications, List.of(), List.of(),
                    manualFileVerification, runState, List.of(),
                    new PreviewEvidence(PreviewEvidence.Status.NOT_REQUESTED, "", "", ""));
        }

        public Input(Long taskId, List<String> changedFiles, List<String> successfulVerifications,
                     List<String> failedVerifications, boolean manualFileVerification, String runState,
                     List<String> unresolvedRisks) {
            this(taskId, changedFiles, successfulVerifications, failedVerifications, List.of(), List.of(),
                    manualFileVerification, runState, unresolvedRisks,
                    new PreviewEvidence(PreviewEvidence.Status.NOT_REQUESTED, "", "", ""));
        }

        public Input {
            changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
            successfulVerifications = List.copyOf(successfulVerifications == null ? List.of() : successfulVerifications);
            failedVerifications = List.copyOf(failedVerifications == null ? List.of() : failedVerifications);
            environmentVerifications = List.copyOf(environmentVerifications == null ? List.of() : environmentVerifications);
            unresolvedToolFailures = List.copyOf(unresolvedToolFailures == null ? List.of() : unresolvedToolFailures);
            unresolvedRisks = List.copyOf(unresolvedRisks == null ? List.of() : unresolvedRisks);
            preview = preview == null ? new PreviewEvidence(PreviewEvidence.Status.NOT_REQUESTED, "", "", "") : preview;
        }
    }
}
