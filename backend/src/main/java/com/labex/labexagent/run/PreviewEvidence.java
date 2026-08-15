package com.labex.labexagent.run;

import com.labex.entity.AgentPreviewRun;
import java.util.Locale;

/** \u5f53\u524d task \u7684\u53d7\u7ba1\u9884\u89c8\u4e8b\u5b9e\u6295\u5f71\uff1b\u4e0d\u4fdd\u5b58\u547d\u4ee4\u539f\u6587\u3002 */
public record PreviewEvidence(Status status, String publicUrl, String failureCode, String outputPath) {
    public PreviewEvidence {
        status = status == null ? Status.NOT_REQUESTED : status;
        publicUrl = safe(publicUrl);
        failureCode = safe(failureCode);
        outputPath = safe(outputPath);
        if (status != Status.READY) {
            publicUrl = "";
        }
    }

    public static PreviewEvidence from(AgentPreviewRun run) {
        if (run == null) {
            return new PreviewEvidence(Status.NOT_REQUESTED, "", "", "");
        }
        return new PreviewEvidence(Status.from(run.getStatus()), run.getPublicUrl(), run.getFailureCode(), run.getOutputPath());
    }

    public boolean ready() {
        return status == Status.READY && !publicUrl.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public enum Status {
        NOT_REQUESTED,
        STARTING,
        READY,
        FAILED,
        STOPPED,
        EXITED,
        UNAVAILABLE_AFTER_RESTART;

        static Status from(String value) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            try {
                return Status.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return FAILED;
            }
        }
    }
}
