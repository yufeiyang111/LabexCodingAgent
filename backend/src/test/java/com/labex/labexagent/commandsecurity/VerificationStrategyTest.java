package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationStrategyTest {

    @TempDir
    Path workspace;

    @Test
    void resolvesMavenCompileInsteadOfSilentlyUsingTest() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");

        TestCommandResolver.ResolvedTestCommand resolved =
                TestCommandResolver.resolveProject(workspace, VerificationStrategy.COMPILE);

        assertThat(resolved.command()).containsExactly("mvn", "compile");
    }

    @Test
    void resolvesMavenOfflineTestAsDirectArguments() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");

        TestCommandResolver.ResolvedTestCommand resolved =
                TestCommandResolver.resolveProject(workspace, VerificationStrategy.OFFLINE_TEST);

        assertThat(resolved.command()).containsExactly("mvn", "-o", "test");
    }

    @Test
    void usesConfiguredFallbackWhenPrimaryStrategyHasNoCommand() throws Exception {
        Files.writeString(workspace.resolve("pom.xml"), "<project />");

        TestCommandResolver.ResolvedTestCommand resolved = TestCommandResolver.resolveProject(
                workspace, VerificationStrategy.MANUAL, VerificationStrategy.COMPILE);

        assertThat(resolved.command()).containsExactly("mvn", "compile");
    }
    @Test
    void autoUsesPackageTestScriptBeforeBuildScript() throws Exception {
        Files.writeString(workspace.resolve("package.json"),
                "{\"scripts\":{\"test\":\"vitest run\",\"build\":\"vite build\"}}");

        TestCommandResolver.ResolvedTestCommand resolved =
                TestCommandResolver.resolveProject(workspace, VerificationStrategy.AUTO);

        assertThat(resolved.command()).containsExactly("npm", "test");
    }
}
