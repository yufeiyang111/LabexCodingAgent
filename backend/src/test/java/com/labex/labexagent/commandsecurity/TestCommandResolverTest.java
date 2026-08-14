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
    @Test
    void prefersTheModuleContainingTheDurableChangedFile() throws Exception {
        Path backend = Files.createDirectories(workspace.resolve("backend"));
        Files.writeString(backend.resolve("pom.xml"), "<project />");
        Path frontend = Files.createDirectories(workspace.resolve("frontend/src"));
        Files.writeString(workspace.resolve("frontend/package.json"),
                "{\"scripts\":{\"build\":\"vite build\"}}");

        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(
                workspace, VerificationStrategy.BUILD, null, java.util.List.of("frontend/src/App.vue"));

        assertEquals(workspace.resolve("frontend"), resolved.workingDirectory());
        assertEquals(java.util.List.of("npm", "run", "build"), resolved.command());
    }

    @Test
    void respectsExplicitPackageManifestWhenWorkspaceAlsoContainsMaven() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");
        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"test\":\"node -e 'process.exit(0)'\"}}");

        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(
                workspace, VerificationStrategy.TEST, null, java.util.List.of("package.json"));

        assertEquals(workspace, resolved.workingDirectory());
        assertEquals(java.util.List.of("npm", "test"), resolved.command());
    }

    @Test
    void reportsWhenChangedFilesSpanMultipleVerificationModules() throws Exception {
        Path backend = Files.createDirectories(workspace.resolve("backend/src"));
        Files.writeString(workspace.resolve("backend/pom.xml"), "<project />");
        Path frontend = Files.createDirectories(workspace.resolve("frontend/src"));
        Files.writeString(workspace.resolve("frontend/package.json"),
                "{\"scripts\":{\"build\":\"vite build\"}}");

        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(
                workspace, VerificationStrategy.BUILD, null,
                java.util.List.of("backend/src/App.java", "frontend/src/App.vue"));

        assertEquals(java.util.List.of(), resolved.command());
        org.junit.jupiter.api.Assertions.assertTrue(resolved.reason().contains("多个模块"));
    }

    @Test
    void assignsLongerDefaultsToDependencyHydratingBuildTools() {
        assertEquals(300, TestCommandResolver.defaultTimeoutSeconds(java.util.List.of("mvn", "test")));
        assertEquals(240, TestCommandResolver.defaultTimeoutSeconds(java.util.List.of("npm", "run", "build")));
        assertEquals(180, TestCommandResolver.defaultTimeoutSeconds(java.util.List.of("python", "-m", "pytest")));
    }

    @Test
    void usesPython3ForPythonProjectVerificationInLinuxWorkers() throws Exception {
        Files.writeString(workspace.resolve("requirements.txt"), "pytest");

        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(workspace);

        assertEquals(java.util.List.of("python3", "-m", "pytest"), resolved.command());
    }

}
