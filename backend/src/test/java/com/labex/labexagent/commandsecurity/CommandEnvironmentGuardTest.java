package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.execution.WorkerShellDescriptor;
import org.junit.jupiter.api.Test;

class CommandEnvironmentGuardTest {
    private static final WorkerShellDescriptor BASH = WorkerShellDescriptor.bash(
            "linux", "/bin/bash", "/workspace", true);

    @Test
    void detectsNodeToolchainForNpmCommands() {
        assertThat(CommandEnvironmentGuard.requiredTools("npm run dev", BASH))
                .containsExactly("node", "npm");
    }

    @Test
    void detectsJavaToolchainForMavenCommands() {
        assertThat(CommandEnvironmentGuard.requiredTools("mvn test", BASH))
                .containsExactly("java", "mvn");
    }

    @Test
    void detectsPythonAndRustToolchains() {
        assertThat(CommandEnvironmentGuard.requiredTools("python3 app.py && cargo test", BASH))
                .containsExactly("python3", "rustc", "cargo");
    }

    @Test
    void ignoresOrdinaryWorkspaceCommands() {
        assertThat(CommandEnvironmentGuard.requiredTools("ls -la && grep -R TODO src", BASH))
                .isEmpty();
    }
}

