package com.labex.labexagent.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.tool.FileContentFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceMutationEvidenceTest {
    @TempDir
    Path workspace;

    @Test
    void snapshotTargetMismatchNeverProjectsVerifiedAfterState() throws Exception {
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        project.setWorkspacePath(workspace.toString());
        Path target = Files.createDirectories(workspace.resolve("src")).resolve("Main.java");
        Files.writeString(target, "actual concurrent content\n", StandardCharsets.UTF_8);
        PendingChange change = new PendingChange("change-1", 9, 3, "conversation-7", 7L, 11L,
                "src/Main.java", "modify", "before\n", "expected after\n",
                "diff --git a/src/Main.java b/src/Main.java", "applied");

        Map<String, Object> verification = WorkspaceMutationEvidence.verifyPostconditions(project, List.of(change));
        Map<String, Object> mutation = WorkspaceMutationEvidence.fromChanges(List.of(change),
                WorkspaceMutationEvidence.snapshotBeforeStates(List.of(change)), verification);

        assertThat(verification).containsEntry("state", "mismatch");
        @SuppressWarnings("unchecked")
        Map<String, Object> targetVerification = (Map<String, Object>) ((List<?>) verification.get("targets")).get(0);
        assertThat(targetVerification)
                .containsEntry("expectedState", "present")
                .containsEntry("observedState", "present")
                .containsEntry("expectedSha256", FileContentFingerprint.sha256("expected after\n"))
                .containsEntry("observedSha256", FileContentFingerprint.sha256("actual concurrent content\n"));
        @SuppressWarnings("unchecked")
        Map<String, Object> targetMutation = (Map<String, Object>) ((List<?>) mutation.get("targets")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) targetMutation.get("after");
        assertThat(after)
                .containsEntry("state", "present")
                .containsEntry("sha256", FileContentFingerprint.sha256("actual concurrent content\n"))
                .containsEntry("verified", false);
        assertThat(mutation.toString()).doesNotContain(workspace.toAbsolutePath().normalize().toString());
    }
}
