package com.labex.labexagent.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.StudentProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSnapshotServiceRenameTest {

    @TempDir
    Path workspace;

    @Test
    void preservesRenameMetadataInChangedFilesAndGitDiff() throws Exception {
        Files.writeString(workspace.resolve("OldName.txt"), "same content\n");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        GitSnapshotService snapshots = new GitSnapshotService();

        GitSnapshotService.Snapshot before = snapshots.capture(project, "before rename");
        Files.move(workspace.resolve("OldName.txt"), workspace.resolve("NewName.txt"));
        GitSnapshotService.Snapshot after = snapshots.capture(project, "after rename");
        GitSnapshotService.ChangedFile rename = snapshots.changedFiles(project, before, after).stream()
                .filter(file -> file.status().startsWith("R"))
                .findFirst()
                .orElseThrow();

        disableDefaultRenameDetection();

        assertEquals("OldName.txt", rename.oldPath());
        assertEquals("NewName.txt", rename.path());
        assertTrue(snapshots.diffForFile(project, before, after, rename).contains("rename from OldName.txt"));
    }

    @Test
    void excludesTopLevelAndNestedIgnoredDirectoriesFromSnapshots() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve("node_modules/package"));
        Files.createDirectories(workspace.resolve("nested/.cache"));
        Files.writeString(workspace.resolve("src/Main.java"), "class Main {}\n");
        Files.writeString(workspace.resolve("node_modules/package/index.js"), "module.exports = 1;\n");
        Files.writeString(workspace.resolve("nested/.cache/cache.bin"), "before\n");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        GitSnapshotService snapshots = new GitSnapshotService();

        GitSnapshotService.Snapshot before = snapshots.capture(project, "before ignored changes");
        Files.writeString(workspace.resolve("src/Main.java"), "class Main { int value; }\n");
        Files.writeString(workspace.resolve("node_modules/package/index.js"), "module.exports = 2;\n");
        Files.writeString(workspace.resolve("nested/.cache/cache.bin"), "after\n");
        GitSnapshotService.Snapshot after = snapshots.capture(project, "after ignored changes");
        List<GitSnapshotService.ChangedFile> changes = snapshots.changedFiles(project, before, after);

        assertTrue(changes.stream().anyMatch(file -> "src/Main.java".equals(file.path())));
        assertFalse(changes.stream().anyMatch(file -> file.path().contains("node_modules")
                || file.path().contains(".cache")));
        String visibleDiff = snapshots.diffForFile(project, before, after,
                changes.stream().filter(file -> "src/Main.java".equals(file.path())).findFirst().orElseThrow());
        assertTrue(visibleDiff.contains("src/Main.java"));
        assertFalse(visibleDiff.contains("node_modules"));
        assertFalse(visibleDiff.contains(".cache"));
    }

    private void disableDefaultRenameDetection() throws Exception {
        Process process = new ProcessBuilder(
                "git",
                "--git-dir",
                workspace.resolve(".labex/git-snapshots").toString(),
                "config",
                "diff.renames",
                "false")
                .redirectErrorStream(true)
                .start();
        assertEquals(0, process.waitFor());
    }
}
