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

    public static String forInteractionResume(Long taskId, String interactionId) {
        return "task-" + String.valueOf(taskId) + "-interaction-resume-"
                + digest(String.valueOf(interactionId));
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
