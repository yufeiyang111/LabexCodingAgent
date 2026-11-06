package com.labex.labexagent.diff;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.git.GitCommandRunner;
import com.labex.labexagent.git.ProjectGitRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GitSnapshotServicePathTest {

    private final GitSnapshotService snapshots = snapshots();

    @Test
    void rejectsEscapingAndAbsolutePathsFromSerializedSnapshots() {
        String escaping = line("M", "", "../outside.txt");
        String absolute = line("M", "", "/outside.txt");

        assertTrue(snapshots.deserializePaths(escaping).isEmpty());
        assertTrue(snapshots.deserializePaths(absolute).isEmpty());
    }

    private GitSnapshotService snapshots() {
        GitCommandRunner git = new GitCommandRunner();
        return new GitSnapshotService(git, new ProjectGitRepository(git));
    }

    private String line(String status, String oldPath, String path) {
        return encode(status) + "\t" + encode(oldPath) + "\t" + encode(path);
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
