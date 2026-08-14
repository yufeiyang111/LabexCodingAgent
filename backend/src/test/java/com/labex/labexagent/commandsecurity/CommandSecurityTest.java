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
    void requiresApprovalForDestructiveDirectCommands() {
        assertRequiresApproval("git reset --hard HEAD");
        assertRequiresApproval("git clean -fd");
        assertRequiresApproval("git checkout -- file");
        assertRequiresApproval("git rm obsolete.txt");
        assertRequiresApproval("git stash drop");
        assertRequiresApproval("git push origin main --force");
        assertRequiresApproval("git push origin main -f");
        assertRequiresApproval("rm obsolete.txt");
        assertRequiresApproval("rmdir empty-dir");
        assertRequiresApproval("truncate -s 0 notes.txt");
        assertRequiresApproval("docker run nginx");
        assertRequiresApproval("drop table posts");
    }

    @Test
    void allowsOrdinaryWorkspaceMutationsWithoutApproval() {
        assertAllows("touch marker");
        assertAllows("mkdir directory");
        assertAllows("mv first second");
        assertAllows("git commit -m change");
        assertAllows("git add pom.xml");
        assertAllows("python -V");
        assertAllows("node .labex-acceptance-command-hold.cjs");
        assertAllows("npm test");
        assertAllows("npm run build");
        assertAllows("npm run arbitrary-script");
        assertAllows("mvn validate");
        assertAllows("mvn compile");
        assertAllows("pytest");
        assertAllows("java -version");
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
        assertBlocked("echo\nok", CommandReasonCode.UNKNOWN_CONTROL_CHARACTER);
        assertBlocked("ba\\sh -c echo", CommandReasonCode.QUOTE_SPLIT_EXECUTABLE);
        assertBlocked("powe^rshell -EncodedCommand AAAA", CommandReasonCode.QUOTE_SPLIT_EXECUTABLE);
    }

    @Test
    void allowsNetworkCommandsAndSafeForcePushesWithoutApproval() {
        assertAllows("curl https://example.invalid");
        assertAllows("wget file.txt");
        assertAllows("git fetch origin");
        assertAllows("git pull origin main");
        assertAllows("git push origin main");
        assertAllows("git push --force-with-lease origin main");
        assertAllows("npm install package");
        assertAllows("pip install requests");
        assertAllows("ping example.invalid");
    }

    @Test
    void allowsUnrecognizedExecutablesToExecuteInsideTheWorker() {
        CommandClassification result = classifier.classify(request("unknown-command argument"));

        assertEquals(CommandDecision.ALLOW, result.decision());
        assertEquals(CommandReasonCode.UNRECOGNIZED_COMMAND, result.reasonCode());
    }

    @Test
    void allowsNetworkRequestWithSafeExecutableBecauseNetworkIsEnabledByDefault() {
        CommandRequest request = new CommandRequest("git status", "direct", ".", 60, false, true, "sandbox-deny-network");

        assertEquals(CommandDecision.ALLOW, classifier.classify(request).decision());
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

    private void assertRequiresApproval(String command, CommandReasonCode reasonCode) {
        CommandClassification result = classifier.classify(request(command));
        assertEquals(CommandDecision.REQUIRE_APPROVAL, result.decision(), command);
        assertEquals(reasonCode, result.reasonCode(), command);
    }

    private void assertAllows(String command) {
        CommandClassification result = classifier.classify(request(command));
        assertEquals(CommandDecision.ALLOW, result.decision(), command);
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
