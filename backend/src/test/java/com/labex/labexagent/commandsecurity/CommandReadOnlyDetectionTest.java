package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 只读 shell 判定只决定“要不要做工作区快照”，因此允许漏判（多记一次快照），
 * 但不允许把会写文件、或能执行任意命令的载荷判成只读。
 */
class CommandReadOnlyDetectionTest {
    private final CommandClassifier classifier = new CommandClassifier();

    @Test
    void inspectionPipelinesAreReadOnly() {
        String command = "cd LabexAgent && echo '=== test dir ===' && find backend/src/test -name '*.java' | wc -l "
                + "&& grep -rl testcontainers backend/pom.xml && ls frontend";

        assertThat(CommandClassifier.readOnlyShellPayload(command)).isTrue();
    }

    @Test
    void quotedOperatorsInsideArgumentsAreNotTreatedAsSeparators() {
        String command = "grep -rl \"Testcontainers|testcontainers\" backend/pom.xml backend/src/test 2>/dev/null "
                + "| head -3";

        assertThat(CommandClassifier.readOnlyShellPayload(command)).isTrue();
    }

    @Test
    void workspaceRedirectionIsNotReadOnly() {
        assertThat(CommandClassifier.readOnlyShellPayload("echo hi > notes.txt")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("find . -name '*.java' >> list.txt")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("cat a.txt | tee b.txt")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("sort -o out.txt in.txt")).isFalse();
    }

    @Test
    void devNullAndDescriptorDuplicationDoNotCountAsWrites() {
        assertThat(CommandClassifier.readOnlyShellPayload("find . -name '*.java' 2>/dev/null | wc -l")).isTrue();
        assertThat(CommandClassifier.readOnlyShellPayload("grep -r foo . 2>&1")).isTrue();
    }

    @Test
    void backgroundExecutionIsNotReadOnly() {
        assertThat(CommandClassifier.readOnlyShellPayload("echo start & rm -rf build")).isFalse();
    }

    @Test
    void findPrimariesAreInspectedIndividually() {
        assertThat(CommandClassifier.readOnlyShellPayload("find . -name '*.java' -exec cat {} + | wc -l")).isTrue();
        assertThat(CommandClassifier.readOnlyShellPayload("find . -name '*.tmp' -delete")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("find . -name '*.class' -exec rm {} ;")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("ls | xargs rm -f")).isFalse();
    }

    @Test
    void commandSubstitutionIsCheckedRecursively() {
        assertThat(CommandClassifier.readOnlyShellPayload("echo \"lines: $(find . -name '*.java' | wc -l)\"")).isTrue();
        assertThat(CommandClassifier.readOnlyShellPayload("echo $(rm -rf build)")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("echo `rm -rf build`")).isFalse();
    }

    @Test
    void unparseablePayloadsAreTreatedAsMutating() {
        assertThat(CommandClassifier.readOnlyShellPayload("echo 'unclosed")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("echo $(unclosed")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("   ")).isFalse();
    }

    @Test
    void executablesOutsideTheInspectionAllowlistAreNotReadOnly() {
        assertThat(CommandClassifier.readOnlyShellPayload("node scripts/generate.js")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("npm run build")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("python3 -c 'open(\"a\",\"w\")'")).isFalse();
    }

    @Test
    void buildToolingIsNeverTreatedAsReadOnly() {
        assertThat(CommandClassifier.readOnlyShellPayload("mvn test")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("mvn -v")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("npm test")).isFalse();
    }

    @Test
    void gitIsReadOnlyOnlyForInspectionSubcommands() {
        assertThat(CommandClassifier.readOnlyShellPayload("git status && git diff --stat")).isTrue();
        assertThat(CommandClassifier.readOnlyShellPayload("git log --oneline -5")).isTrue();
        assertThat(CommandClassifier.readOnlyShellPayload("git reset --hard HEAD")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("git clean -fd")).isFalse();
        assertThat(CommandClassifier.readOnlyShellPayload("git checkout -- .")).isFalse();
    }

    @Test
    void classificationExposesReadOnlyShellForTheSnapshotGate() {
        CommandClassification inspection = classifier.classify(
                new CommandRequest("find . -name '*.java' | wc -l", "shell", ".", 60, false, false, "labex-standard"));
        CommandClassification mutation = classifier.classify(
                new CommandRequest("find . -name '*.java' | wc -l > out.txt", "shell", ".", 60, false, false,
                        "labex-standard"));

        assertThat(inspection.decision()).isEqualTo(CommandDecision.ALLOW);
        assertThat(inspection.readOnlyShell()).isTrue();
        assertThat(mutation.readOnlyShell()).isFalse();
    }
}
