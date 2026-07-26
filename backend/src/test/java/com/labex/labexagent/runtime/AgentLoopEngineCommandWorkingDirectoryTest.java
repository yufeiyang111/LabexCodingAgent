package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoopEngineCommandWorkingDirectoryTest {

    @TempDir
    Path workspace;

    @Test
    void usesDetectedSubprojectDirectoryForRunTestsApproval() throws Exception {
        Files.createDirectories(workspace.resolve("backend"));
        Files.writeString(workspace.resolve("backend/pom.xml"), "<project/>");
        Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(workspace.resolve("frontend/package.json"), "{}");

        assertThat(AgentLoopEngine.commandWorkingDirectory("run_tests", workspace)).isEqualTo("backend");
        assertThat(AgentLoopEngine.commandWorkingDirectory("shell", workspace)).isEqualTo(".");
    }
}
