package com.labex.labexagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShellCommandFactoryTest {

    @Test
    void keepsBashCommandAsOneUntouchedPayload() {
        String command = "cd frontend&&printf \"hello world\" > result.txt | cat && echo $HOME";
        WorkerShellDescriptor descriptor = WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true);

        assertThat(ShellCommandFactory.create(descriptor, command))
                .containsExactly("/bin/bash", "--noprofile", "--norc", "-lc", command);
    }

    @Test
    void usesNonInteractivePowerShellArgumentsWithoutSplittingPayload() {
        String command = "Set-Location frontend; npm install; Write-Output $HOME";
        WorkerShellDescriptor descriptor = WorkerShellDescriptor.powerShell(
                "windows", "pwsh.exe", "C:/workspace", true);

        assertThat(ShellCommandFactory.create(descriptor, command))
                .containsExactly("pwsh.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command);
    }

    @Test
    void rendersServerSelectedArgumentsAsAQuotedBashPayload() {
        WorkerShellDescriptor descriptor = WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true);

        assertThat(ShellCommandFactory.renderArguments(descriptor,
                List.of("npm", "run", "test", "--", "name with spaces", "it's-safe")))
                .isEqualTo("'npm' 'run' 'test' '--' 'name with spaces' 'it'\"'\"'s-safe'");
    }

    @Test
    void rendersServerSelectedArgumentsAsAQuotedPowerShellPayload() {
        WorkerShellDescriptor descriptor = WorkerShellDescriptor.powerShell(
                "windows", "powershell.exe", "C:/workspace", true);

        assertThat(ShellCommandFactory.renderArguments(descriptor,
                List.of("npm", "run", "build", "it's-safe")))
                .isEqualTo("& 'npm' 'run' 'build' 'it''s-safe'");
    }

    @Test
    void rejectsAnEmptyShellPayload() {
        WorkerShellDescriptor descriptor = WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true);

        assertThatThrownBy(() -> ShellCommandFactory.create(descriptor, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }
}