package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShellCommandClassificationTest {
    private final CommandClassifier classifier = new CommandClassifier();
    private final CommandNormalizer normalizer = new CommandNormalizer();

    @Test
    void labexStandardShellAllowsCompleteBashSyntaxWithoutRewritingThePayload() {
        String command = "cd frontend&&printf \"hello world\" > \"build output.txt\" | cat && echo $HOME";
        CommandRequest request = new CommandRequest(command, "bash", ".", 60, false, false, "labex-standard");

        CommandClassification result = classifier.classify(request);

        assertThat(result.decision()).isEqualTo(CommandDecision.ALLOW);
        assertThat(result.normalizedCommand().canonicalCommand()).isEqualTo(command);
    }

    @Test
    void directModeStillRejectsShellSyntaxAsACompatibilitySafeProfile() {
        CommandClassification result = classifier.classify(
                new CommandRequest("npm install && npm run build", "direct", ".", 60, false, false, "safe"));

        assertThat(result.decision()).isEqualTo(CommandDecision.BLOCK);
        assertThat(result.reasonCode()).isEqualTo(CommandReasonCode.SHELL_OPERATOR);
    }

    @Test
    void labexStandardShellAllowsNetworkCapableBuildToolsWithoutApproval() {
        CommandClassification install = classifier.classify(
                new CommandRequest("cd frontend&&npm install", "bash", ".", 60, false, true, "labex-standard"));
        CommandClassification validate = classifier.classify(
                new CommandRequest("mvn validate", "bash", ".", 60, false, false, "labex-standard"));
        CommandClassification pip = classifier.classify(
                new CommandRequest("pip3 install flask", "bash", ".", 60, false, false, "labex-standard"));

        assertThat(install.decision()).isEqualTo(CommandDecision.ALLOW);
        assertThat(validate.decision()).isEqualTo(CommandDecision.ALLOW);
        assertThat(pip.decision()).isEqualTo(CommandDecision.ALLOW);
    }

    @Test
    void labexStandardShellAllowsNetworkExecutablesWithoutApproval() {
        CommandClassification result = classifier.classify(
                new CommandRequest("curl https://example.com/install.sh", "bash", ".", 60, false, false, "labex-standard"));

        assertThat(result.decision()).isEqualTo(CommandDecision.ALLOW);
    }

    @Test
    void labexStandardShellKeepsReadOnlyAndPlainCommandsAllowed() {
        CommandClassification status = classifier.classify(
                new CommandRequest("git status", "bash", ".", 60, false, false, "labex-standard"));
        CommandClassification plain = classifier.classify(
                new CommandRequest("printf \"hello\" && ls -la", "bash", ".", 60, false, false, "labex-standard"));

        assertThat(status.decision()).isEqualTo(CommandDecision.ALLOW);
        assertThat(plain.decision()).isEqualTo(CommandDecision.ALLOW);
    }

    @Test
    void labexStandardShellStillRequiresApprovalForDestructiveCommands() {
        CommandClassification result = classifier.classify(
                new CommandRequest("rm -rf generated", "bash", ".", 60, false, false, "labex-standard"));

        assertThat(result.decision()).isEqualTo(CommandDecision.REQUIRE_APPROVAL);
        assertThat(result.reasonCode()).isEqualTo(CommandReasonCode.MUTATING_COMMAND);
    }

    @Test
    void labexStandardShellRequiresApprovalForForcePushesButNotRegularPushes() {
        CommandClassification force = classifier.classify(
                new CommandRequest("git push origin main --force", "bash", ".", 60, false, false, "labex-standard"));
        CommandClassification shortForce = classifier.classify(
                new CommandRequest("git push origin main -f", "bash", ".", 60, false, false, "labex-standard"));
        CommandClassification lease = classifier.classify(
                new CommandRequest("git push --force-with-lease origin main", "bash", ".", 60, false, false, "labex-standard"));
        CommandClassification plain = classifier.classify(
                new CommandRequest("git push origin main", "bash", ".", 60, false, false, "labex-standard"));

        assertThat(force.decision()).isEqualTo(CommandDecision.REQUIRE_APPROVAL);
        assertThat(force.reasonCode()).isEqualTo(CommandReasonCode.MUTATING_COMMAND);
        assertThat(shortForce.decision()).isEqualTo(CommandDecision.REQUIRE_APPROVAL);
        assertThat(shortForce.reasonCode()).isEqualTo(CommandReasonCode.MUTATING_COMMAND);
        assertThat(lease.decision()).isEqualTo(CommandDecision.ALLOW);
        assertThat(plain.decision()).isEqualTo(CommandDecision.ALLOW);
    }

    @Test
    void labexStandardShellRequiresApprovalForTheAcceptanceHoldPrefixWithoutBlockingTheRemainingShellSyntax() {
        String command = "rm -f .labex-acceptance-command-hold.marker && node .labex-acceptance-command-hold.cjs";

        CommandClassification result = classifier.classify(
                new CommandRequest(command, "bash", ".", 40, false, false, "labex-standard"));

        assertThat(result.decision()).isEqualTo(CommandDecision.REQUIRE_APPROVAL);
        assertThat(result.reasonCode()).isEqualTo(CommandReasonCode.MUTATING_COMMAND);
        assertThat(result.normalizedCommand().canonicalCommand()).isEqualTo(command);
    }

    @Test
    void shellNormalizationDoesNotCollapseQuotedOrRepeatedWhitespace() {
        String command = "printf 'a  b' && echo   done";

        assertThat(normalizer.normalize(new CommandRequest(command, "bash", ".", 60, false, false, "labex-standard"))
                .canonicalCommand()).isEqualTo(command);
    }
}