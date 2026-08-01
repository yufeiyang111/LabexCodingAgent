package com.labex.labexagent.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates deterministic, non-sensitive idempotency keys for logical task state changes. */
public final class AgentRunTransitionKey {
    private AgentRunTransitionKey() {
    }

    public static String forTaskUpdate(Long taskId, String status, String currentStep, String summary) {
        return "task-" + String.valueOf(taskId) + "-transition-"
                + digest(String.valueOf(status), String.valueOf(currentStep), String.valueOf(summary));
    }

    public static String forTaskUpdateOccurrence(Long taskId, Object occurrence,
                                                 String status, String currentStep, String summary) {
        return "task-" + String.valueOf(taskId) + "-occurrence-"
                + digest(String.valueOf(occurrence), String.valueOf(status),
                String.valueOf(currentStep), String.valueOf(summary));
    }

    public static String forInteractionResume(Long taskId, String interactionId) {
        return "task-" + String.valueOf(taskId) + "-interaction-resume-"
                + digest(String.valueOf(interactionId));
    }

    /** 为一次已完成的一次性命令审批恢复生成稳定幂等键。 */
    public static String forCommandApprovalResume(Long taskId, String approvalId) {
        return "task-" + String.valueOf(taskId) + "-command-approval-resume-"
                + digest(String.valueOf(approvalId));
    }

    /**
     * 为一次具体的暂停交互生成稳定键。同一审批重放时键不变，不同审批即使文案相同也不会冲突。
     */
    public static String forPause(Long taskId, String pauseType, String pauseId,
                                  String status, String currentStep, String summary) {
        return "task-" + String.valueOf(taskId) + "-pause-"
                + digest(String.valueOf(pauseType), String.valueOf(pauseId), String.valueOf(status),
                String.valueOf(currentStep), String.valueOf(summary));
    }

    public static String forResumedRunUpdate(Long taskId, Object resumeInstance,
                                             String status, String currentStep, String summary) {
        return "task-" + String.valueOf(taskId) + "-resumed-run-"
                + digest(String.valueOf(resumeInstance), String.valueOf(status),
                String.valueOf(currentStep), String.valueOf(summary));
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) 0);
            }
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 12; index++) {
                result.append(String.format("%02x", hash[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
