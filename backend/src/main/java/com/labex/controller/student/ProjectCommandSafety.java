package com.labex.controller.student;

import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandReasonCode;
import com.labex.labexagent.commandsecurity.CommandRequest;

/**
 * Compatibility facade for legacy terminal callers. Classification is pure; the legacy approved
 * argument is intentionally ignored and cannot turn an approval-required command into an allow.
 */
public final class ProjectCommandSafety {
    private static final CommandClassifier CLASSIFIER = new CommandClassifier();

    private ProjectCommandSafety() {
    }

    public static SafetyCheck check(String command, boolean approved) {
        return from(CLASSIFIER.classify(new CommandRequest(command)));
    }

    public static SafetyCheck check(String command) {
        return from(CLASSIFIER.classify(new CommandRequest(command)));
    }

    private static SafetyCheck from(CommandClassification classification) {
        String level = switch (classification.decision()) {
            case ALLOW -> "safe";
            case REQUIRE_APPROVAL -> "approval_required";
            case BLOCK -> "blocked";
        };
        String keyword = legacyKeyword(classification.reasonCode());
        String message = switch (classification.decision()) {
            case ALLOW -> "ok";
            case REQUIRE_APPROVAL -> "command requires a one-time approval";
            case BLOCK -> "command blocked by restricted command policy: " + classification.reasonCode().name().toLowerCase();
        };
        return new SafetyCheck(classification.decision() == CommandDecision.ALLOW,
                classification.decision() == CommandDecision.REQUIRE_APPROVAL,
                level, keyword, message, classification);
    }

    private static String legacyKeyword(CommandReasonCode reasonCode) {
        return switch (reasonCode) {
            case PROMPT_INJECTION -> "prompt_injection";
            case HARD_BLOCKED_COMMAND -> "hard_blocked_command";
            case MUTATING_COMMAND -> "mutating_command";
            default -> reasonCode.name().toLowerCase();
        };
    }

    public static final class SafetyCheck {
        private final boolean safe;
        private final boolean requiresApproval;
        private final String level;
        private final String keyword;
        private final String reason;
        private final CommandClassification classification;

        private SafetyCheck(boolean safe, boolean requiresApproval, String level, String keyword, String reason,
                            CommandClassification classification) {
            this.safe = safe;
            this.requiresApproval = requiresApproval;
            this.level = level;
            this.keyword = keyword;
            this.reason = reason;
            this.classification = classification;
        }

        public boolean allowed() { return safe; }
        public boolean approvalRequired() { return requiresApproval; }
        public String message() { return reason; }
        public String riskLevel() { return level; }
        public String matchedRule() { return keyword; }
        public boolean isSafe() { return safe; }
        public boolean isRequiresApproval() { return requiresApproval; }
        public String getLevel() { return level; }
        public String getKeyword() { return keyword; }
        public String getReason() { return reason; }
        public CommandClassification classification() { return classification; }
    }
}
