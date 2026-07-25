package com.labex.labexagent.run;

import org.springframework.stereotype.Component;

@Component
public final class BackgroundDeliveryPolicy {
    public Decision evaluate(Request request) {
        if (request == null || request.action() == null) return Decision.deny("delivery action is required");
        if (!request.agentOwnedBranch()) return Decision.deny("human-managed branches are protected");
        if (!request.explicitApproval()) return Decision.deny("explicit approval is required");
        if (!request.verificationPassed()) return Decision.deny("verification must pass before delivery");
        return Decision.permit();
    }

    public enum Action { COMMIT, PUSH, PULL_REQUEST }
    public record Request(Action action, boolean agentOwnedBranch, boolean explicitApproval, boolean verificationPassed) { }
    public record Decision(boolean allowed, String reason) {
        static Decision permit() { return new Decision(true, ""); }
        static Decision deny(String reason) { return new Decision(false, reason); }
    }
}
