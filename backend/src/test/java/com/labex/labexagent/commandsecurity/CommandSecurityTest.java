package com.labex.labexagent.commandsecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CommandSecurityTest {
    private final CommandClassifier classifier = new CommandClassifier();
    private final CommandNormalizer normalizer = new CommandNormalizer();

    @Test
    void allowsDirectLiteralCommand() {
        CommandClassification result = classifier.classify(request("git status"));

        assertEquals(CommandDecision.ALLOW, result.decision());
        assertEquals(CommandReasonCode.SAFE_DIRECT_COMMAND, result.reasonCode());
        assertEquals(CommandRiskClass.SAFE, result.riskClass());
    }

    @Test
    void requiresApprovalForMutatingDirectCommand() {
        CommandClassification result = classifier.classify(request("git reset --hard HEAD"));

        assertEquals(CommandDecision.REQUIRE_APPROVAL, result.decision());
        assertEquals(CommandReasonCode.MUTATING_COMMAND, result.reasonCode());
    }

    @Test
    void blocksRestrictedShellSyntaxAndNetworkPaths() {
        assertBlocked("echo one | cat", CommandReasonCode.SHELL_OPERATOR);
        assertBlockedWithHint("echo one | cat", CommandReasonCode.SHELL_OPERATOR, "\u8bf7\u62c6\u5206\u4e3a\u591a\u4e2a\u72ec\u7acb\u5de5\u5177\u8c03\u7528");
        assertBlocked("echo one; pwd", CommandReasonCode.SHELL_OPERATOR);
        assertBlocked("echo one && pwd", CommandReasonCode.SHELL_OPERATOR);
        assertBlocked("echo one || pwd", CommandReasonCode.SHELL_OPERATOR);
        assertBlocked("echo one > result.txt", CommandReasonCode.REDIRECTION);
        assertBlocked("echo $(whoami)", CommandReasonCode.COMMAND_SUBSTITUTION);
        assertBlocked("echo `whoami`", CommandReasonCode.COMMAND_SUBSTITUTION);
        assertBlocked("echo $HOME", CommandReasonCode.VARIABLE_EXPANSION);
        assertBlocked("type %USERPROFILE%", CommandReasonCode.WINDOWS_VARIABLE_EXPANSION);
        assertBlocked("echo abc | base64 -d", CommandReasonCode.SHELL_OPERATOR);
        assertBlocked("bash -c 'echo hi'", CommandReasonCode.SHELL_COMMAND_STRING);
        assertBlocked("powershell -EncodedCommand ZQBjAGgAbwA=", CommandReasonCode.POWERSHELL_ENCODED_COMMAND);
        assertBlocked("r'm' -rf /", CommandReasonCode.QUOTE_SPLIT_EXECUTABLE);
        assertBlocked("curl https://example.invalid", CommandReasonCode.NETWORK_URL);
        assertBlocked("wget file.txt", CommandReasonCode.NETWORK_COMMAND);
        assertBlocked("echo\nok", CommandReasonCode.UNKNOWN_CONTROL_CHARACTER);
        assertBlocked("ba\\sh -c echo", CommandReasonCode.QUOTE_SPLIT_EXECUTABLE);
        assertBlocked("powe^rshell -EncodedCommand AAAA", CommandReasonCode.QUOTE_SPLIT_EXECUTABLE);
        assertBlocked("git fetch origin", CommandReasonCode.NETWORK_COMMAND);
        assertBlocked("npm install package", CommandReasonCode.NETWORK_COMMAND);
        assertRequiresApproval("npm test");
        assertRequiresApproval("npm run build");
        assertBlocked("npm run arbitrary-script", CommandReasonCode.UNSUPPORTED_SYNTAX);
        assertBlocked("ping example.invalid", CommandReasonCode.NETWORK_COMMAND);
        assertBlocked("echobad", CommandReasonCode.UNKNOWN_CONTROL_CHARACTER);
    }

    @Test
    void requiresApprovalForOrdinaryDirectMutations() {
        assertRequiresApproval("touch marker");
        assertRequiresApproval("mkdir directory");
        assertRequiresApproval("mv first second");
        assertRequiresApproval("git commit -m change");
        assertRequiresApproval("python -V");
    }

    @Test
    void blocksUnsupportedExecutableInsteadOfPassingItToShell() {
        assertBlocked("unknown-command argument", CommandReasonCode.UNSUPPORTED_SYNTAX);
    }

    @Test
    void blocksRequestForNetworkEvenWithSafeExecutable() {
        CommandRequest request = new CommandRequest("git status", "direct", ".", 60, false, true, "sandbox-deny-network");

        assertEquals(CommandDecision.BLOCK, classifier.classify(request).decision());
        assertEquals(CommandReasonCode.NETWORK_COMMAND, classifier.classify(request).reasonCode());
    }

    @Test
    void digestBindsEveryExecutionAffectingField() {
        String baseline = digest(request("git status"));

        assertNotEquals(baseline, digest(request("git diff")));
        assertNotEquals(baseline, digest(new CommandRequest("git status", "direct", "src", 60, false, false, "sandbox-deny-network")));
        assertNotEquals(baseline, digest(new CommandRequest("git status", "direct", ".", 61, false, false, "sandbox-deny-network")));
        assertNotEquals(baseline, digest(new CommandRequest("git status", "direct", ".", 60, true, false, "sandbox-deny-network")));
        assertNotEquals(baseline, digest(new CommandRequest("git status", "direct", ".", 60, false, true, "sandbox-deny-network")));
        assertNotEquals(baseline, digest(new CommandRequest("git status", "direct", ".", 60, false, false, "sandbox-restricted")));
    }

    @Test
    void canonicalizesEquivalentRelativeWorkingDirectoriesWithoutCollidingEscapes() {
        assertEquals(digest(new CommandRequest("git status", "direct", "./src/../.", 60, false, false, "sandbox-deny-network")),
                digest(new CommandRequest("git status", "direct", ".", 60, false, false, "sandbox-deny-network")));
        assertNotEquals(digest(new CommandRequest("git status", "direct", "../one", 60, false, false, "sandbox-deny-network")),
                digest(new CommandRequest("git status", "direct", "../two", 60, false, false, "sandbox-deny-network")));
        assertNotEquals(digest(new CommandRequest("git status", "direct", "/src", 60, false, false, "sandbox-deny-network")),
                digest(new CommandRequest("git status", "direct", "src", 60, false, false, "sandbox-deny-network")));
    }

    private CommandRequest request(String command) {
        return new CommandRequest(command, "direct", ".", 60, false, false, "sandbox-deny-network");
    }

    private String digest(CommandRequest request) {
        return normalizer.normalize(request).digest();
    }

    private void assertRequiresApproval(String command) {
        CommandClassification result = classifier.classify(request(command));
        assertEquals(CommandDecision.REQUIRE_APPROVAL, result.decision(), command);
        assertEquals(CommandReasonCode.MUTATING_COMMAND, result.reasonCode(), command);
    }

    private void assertBlocked(String command, CommandReasonCode reasonCode) {
        CommandClassification result = classifier.classify(request(command));
        assertEquals(CommandDecision.BLOCK, result.decision(), command);
        assertEquals(reasonCode, result.reasonCode(), command);
    }

    private void assertBlockedWithHint(String command, CommandReasonCode reasonCode, String hint) {
        CommandClassification result = classifier.classify(request(command));
        assertEquals(CommandDecision.BLOCK, result.decision(), command);
        assertEquals(reasonCode, result.reasonCode(), command);
        org.junit.jupiter.api.Assertions.assertTrue(
                CommandPolicyMessage.forReason(result.reasonCode()).contains(hint), command);
    }
}
