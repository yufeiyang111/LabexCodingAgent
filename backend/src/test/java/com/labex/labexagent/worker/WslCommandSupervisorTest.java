package com.labex.labexagent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.runtime.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WslCommandSupervisorTest {

    @TempDir
    Path workspace;

    @Test
    void stagesComplexBashPayloadInsteadOfPassingItThroughWslCommandLine() throws Exception {
        String payload = "printf '%s' \"$HOME\" > \"shell output.txt\" && cat \"shell output.txt\"";
        ProcessExecutionRequest request = new ProcessExecutionRequest(
                List.of("/bin/bash", "--noprofile", "--norc", "-lc", payload),
                workspace, Duration.ofSeconds(30), 10_000);
        WorkerRunSpec run = WorkerRunSpec.forWorkspace("wsl-supervisor-shell-payload", workspace, true);

        try (WslCommandSupervisor.Execution execution = WslCommandSupervisor.open(
                run, request, CancellationToken.none())) {
            List<String> command = execution.command();

            assertThat(command).doesNotContain(payload);
            String stagedScript = command.get(command.size() - 1);
            assertThat(stagedScript).endsWith("/command.sh");
            Path hostScript = findGeneratedCommandScript();
            assertThat(Files.readString(hostScript, StandardCharsets.UTF_8)).isEqualTo(payload);
        }
    }

    private Path findGeneratedCommandScript() throws Exception {
        try (var paths = Files.walk(workspace.resolve(".labex-agent/worker-tmp"))) {
            return paths.filter(path -> path.getFileName().toString().equals("command.sh"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
