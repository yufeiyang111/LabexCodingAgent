package com.labex.labexagent.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.StudentProject;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSnapshotServiceTreeSnapshotTest {

    @TempDir
    Path workspace;

    @Test
    void pathScopedCaptureDoesNotPersistASharedWorkspaceIndex() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Main.java"), "class Main {}\n");
        StudentProject project = project();
        GitSnapshotService snapshots = new GitSnapshotService();

        GitSnapshotService.Snapshot before = capturePaths(snapshots, project, "before", List.of("src/Main.java"));
        Files.writeString(workspace.resolve("src/Main.java"), "class Main { int value; }\n");
        GitSnapshotService.Snapshot after = capturePaths(snapshots, project, "after", List.of("src/Main.java"));

        assertTrue(before.available());
        assertTrue(after.available());
        assertFalse(Files.exists(workspace.resolve(".labex/git-snapshots/index")),
                "path-scoped captures must not retain a shared index that can accumulate generated workspace files");
    }

    @Test
    void pathScopedCaptureUsesTreeRefsAndDoesNotTrackUnrelatedFiles() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Main.java"), "class Main {}\n");
        Files.writeString(workspace.resolve("notes.txt"), "baseline\n");
        StudentProject project = project();
        GitSnapshotService snapshots = new GitSnapshotService();

        GitSnapshotService.Snapshot before = capturePaths(snapshots, project, "before", List.of("src/Main.java"));
        Files.writeString(workspace.resolve("src/Main.java"), "class Main { int value; }\n");
        Files.writeString(workspace.resolve("notes.txt"), "unrelated change\n");
        GitSnapshotService.Snapshot after = capturePaths(snapshots, project, "after", List.of("src/Main.java"));

        List<GitSnapshotService.ChangedFile> changes = snapshots.changedFiles(project, before, after);

        assertTrue(before.available());
        assertTrue(after.available());
        assertEquals("tree", objectType(before.ref()));
        assertEquals("tree", objectType(after.ref()));
        assertTrue(changes.stream().anyMatch(file -> "src/Main.java".equals(file.path())));
        assertFalse(changes.stream().anyMatch(file -> "notes.txt".equals(file.path())));

        assertTrue(snapshots.restore(project, before.ref(), snapshots.serializePaths(changes)));
        assertEquals("class Main {}\n", Files.readString(workspace.resolve("src/Main.java")));
        assertEquals("unrelated change\n", Files.readString(workspace.resolve("notes.txt")));
    }

    private GitSnapshotService.Snapshot capturePaths(GitSnapshotService snapshots, StudentProject project,
                                                      String label, List<String> paths) throws Exception {
        boolean methodExists = java.util.Arrays.stream(GitSnapshotService.class.getMethods())
                .anyMatch(method -> method.getName().equals("capture")
                        && java.util.Arrays.equals(method.getParameterTypes(),
                        new Class<?>[]{StudentProject.class, String.class, Collection.class}));
        assertTrue(methodExists, "GitSnapshotService must expose path-scoped capture(project, label, paths)");
        Method method = GitSnapshotService.class.getMethod("capture", StudentProject.class, String.class, Collection.class);
        return (GitSnapshotService.Snapshot) method.invoke(snapshots, project, label, paths);
    }

    private String objectType(String ref) throws Exception {
        Process process = new ProcessBuilder(
                "git",
                "--git-dir", workspace.resolve(".labex/git-snapshots").toString(),
                "cat-file", "-t", ref)
                .redirectErrorStream(true)
                .start();
        assertEquals(0, process.waitFor());
        return new String(process.getInputStream().readAllBytes()).trim();
    }

    private StudentProject project() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        return project;
    }
}