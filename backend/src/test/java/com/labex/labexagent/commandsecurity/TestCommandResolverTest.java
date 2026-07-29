package com.labex.labexagent.commandsecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCommandResolverTest {

    @TempDir
    Path workspace;

    @Test
    void detectsAFrontendProjectInAnImmediateChildDirectory() throws Exception {
        Path frontend = Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(frontend.resolve("package.json"), "{\"scripts\":{\"build\":\"vite build\"}}");

        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(workspace);

        assertEquals(frontend, resolved.workingDirectory());
        assertEquals(java.util.List.of("npm", "run", "build"), resolved.command());
    }

    @Test
    void doesNotInventNpmTestWhenPackageHasNoVerificationScripts() throws Exception {
        Files.writeString(workspace.resolve("package.json"), "{}");

        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(workspace);

        assertEquals(java.util.List.of(), resolved.command());
    }
}
