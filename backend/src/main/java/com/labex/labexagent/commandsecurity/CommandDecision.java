package com.labex.labexagent.commandsecurity;

/** Stable command-policy outcomes. Approval is deliberately not an input to classification. */
public enum CommandDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    BLOCK
}
