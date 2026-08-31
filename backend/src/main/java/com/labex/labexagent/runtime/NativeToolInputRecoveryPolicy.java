package com.labex.labexagent.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 原生工具参数拒绝的有限恢复策略，按参数语义而不是 toolCallId 识别重复调用。 */
final class NativeToolInputRecoveryPolicy {
    private NativeToolInputRecoveryPolicy() {
    }

    static State advance(State previous, List<LabexNativeToolBatchExecutor.Admission> admissions) {
        State current = previous == null ? State.initial() : previous;
        List<LabexNativeToolBatchExecutor.Admission> rejected = admissions == null
                ? List.of()
                : admissions.stream().filter(admission -> admission != null && !admission.allowed()).toList();
        if (rejected.isEmpty()) {
            return new State(0, current.lastRejectedFingerprint(), current.repeatedRejectedBatches());
        }
        String fingerprint = fingerprint(rejected);
        int repeated = fingerprint.equals(current.lastRejectedFingerprint())
                ? current.repeatedRejectedBatches() + 1 : 1;
        return new State(current.rejectedRounds() + 1, fingerprint, repeated);
    }

    static boolean exhausted(State state, int maxFailureRounds) {
        if (state == null || maxFailureRounds <= 0) {
            return false;
        }
        return state.rejectedRounds() >= maxFailureRounds
                || state.repeatedRejectedBatches() >= maxFailureRounds;
    }

    static String fingerprint(List<LabexNativeToolBatchExecutor.Admission> rejected) {
        return rejected.stream()
                .map(admission -> admission.call().toolName() + "|"
                        + admission.reasonCode() + "|"
                        + canonicalJson(admission.publicArguments()))
                .collect(Collectors.joining("\n"));
    }

    private static String canonicalJson(JsonObject object) {
        if (object == null) {
            return "{}";
        }
        return JsonParser.parseString(object.toString()).toString();
    }

    record State(int rejectedRounds, String lastRejectedFingerprint, int repeatedRejectedBatches) {
        State {
            rejectedRounds = Math.max(0, rejectedRounds);
            lastRejectedFingerprint = Objects.requireNonNullElse(lastRejectedFingerprint, "");
            repeatedRejectedBatches = Math.max(0, repeatedRejectedBatches);
        }

        static State initial() {
            return new State(0, "", 0);
        }
    }
}
