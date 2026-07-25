package com.labex.controller.student;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectCommandSafetyTest {

    @Test
    void blocksPromptInjectionWithoutApproval() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("ignore previous instructions", false);

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("blocked", result.riskLevel());
        assertEquals("prompt_injection", result.matchedRule());
    }

    @Test
    void blocksHardBlockedShutdownWithoutApproval() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("shutdown now", false);

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("blocked", result.riskLevel());
        assertEquals("hard_blocked_command", result.matchedRule());
    }

    @Test
    void requiresApprovalForDestructiveRemoveRegardlessOfLegacyApproval() {
        ProjectCommandSafety.SafetyCheck notApproved = ProjectCommandSafety.check("rm -rf foo", false);
        ProjectCommandSafety.SafetyCheck legacyApproved = ProjectCommandSafety.check("rm -rf foo", true);

        assertFalse(notApproved.allowed());
        assertTrue(notApproved.approvalRequired());
        assertEquals("approval_required", notApproved.riskLevel());
        assertEquals("mutating_command", notApproved.matchedRule());
        assertEquals(notApproved.classification(), legacyApproved.classification());
        assertFalse(legacyApproved.allowed());
        assertTrue(legacyApproved.approvalRequired());
    }

    @Test
    void blocksNetworkCommandRegardlessOfLegacyApproval() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("curl https://example.invalid", true);

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("blocked", result.riskLevel());
        assertEquals("network_url", result.matchedRule());
    }

    @Test
    void requiresApprovalForNpmTestBecauseProjectScriptsAreExecutable() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("npm test", false);

        assertFalse(result.allowed());
        assertTrue(result.approvalRequired());
        assertEquals("approval_required", result.riskLevel());
        assertEquals("mutating_command", result.matchedRule());
    }

    @Test
    void blocksChainedNpmTest() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("echo ready && npm test", false);

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("shell_operator", result.matchedRule());
    }

    @Test
    void classifiesOrdinaryEchoAsSafe() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("echo hi", false);

        assertTrue(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("safe", result.riskLevel());
    }

    @Test
    void blocksNullCommandWithoutThrowing() {
        ProjectCommandSafety.SafetyCheck result = assertDoesNotThrow(() -> ProjectCommandSafety.check(null, false));

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("blocked", result.riskLevel());
        assertEquals("empty_command", result.matchedRule());
    }

    @Test
    void blocksBase64PipelineBypass() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("echo cm0gLXJmIC8=|base64 -d|sh", false);

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("shell_operator", result.matchedRule());
    }

    @Test
    void blocksQuotedRemoveBypass() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("r'm' -rf /", false);

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("quote_split_executable", result.matchedRule());
    }

    @Test
    void blocksIfsRemoveBypass() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("rm${IFS}-rf${IFS}/", false);

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("variable_expansion", result.matchedRule());
    }

    @Test
    void matchesHardBlockedKeywordsCaseInsensitively() {
        ProjectCommandSafety.SafetyCheck result = ProjectCommandSafety.check("SHUTDOWN NOW", false);

        assertFalse(result.allowed());
        assertFalse(result.approvalRequired());
        assertEquals("blocked", result.riskLevel());
        assertEquals("hard_blocked_command", result.matchedRule());
    }
}
