package com.labex.labexagent.commandsecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.LocalProcessExecutor;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.worker.LocalDevelopmentWorker;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentApprovedCommandCancellationIntegrationTest {

    @TempDir
    Path workspace;

    @Test
    void cancellationAwareExecutorStopsTheApprovedProcessTree() throws Exception {
        AgentApprovedCommandExecutor executor = new AgentApprovedCommandExecutor(
                new LocalDevelopmentWorker(new LocalProcessExecutor()));
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        AgentCancellationRegistry.ActiveRun activeRun = registry.register("session-71", 7, 12, 71L);
        Path startedMarker = workspace.resolve("approved-command-started");
        Files.writeString(workspace.resolve("LongRunningApprovedCommand.java"), """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class LongRunningApprovedCommand {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "started");
                        Thread.sleep(30000L);
                    }
                }
                """);
        ExecutorService processThread = Executors.newSingleThreadExecutor();
        try {
            Method method = AgentApprovedCommandExecutor.class.getMethod(
                    "execute", CommandApproval.class, StudentProject.class, CancellationToken.class);
            Future<ProcessExecutionResult> resultFuture = processThread.submit(() ->
                    (ProcessExecutionResult) method.invoke(executor, approval(startedMarker), project(), activeRun));
            waitForFile(startedMarker, Duration.ofSeconds(3));

            long cancellationStarted = System.nanoTime();
            registry.cancel("session-71", 7, 12);
            ProcessExecutionResult result = resultFuture.get(3, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancellationStarted);

            assertEquals(ExecutionStatus.CANCELLED, result.status());
            assertTrue(elapsedMillis < 2_500L, "Approved command cancellation took " + elapsedMillis + "ms");
        } finally {
            registry.complete(activeRun);
            processThread.shutdownNow();
        }
    }

    private CommandApproval approval(Path startedMarker) {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-71");
        approval.setStudentId(7);
        approval.setProjectId(12);
        approval.setTaskId(71L);
        approval.setConversationId("conversation-71");
        approval.setSessionId("session-71");
        approval.setSource("agent_shell");
        approval.setStatus("consumed");
        approval.setCanonicalCommand("java LongRunningApprovedCommand.java " + startedMarker.getFileName());
        approval.setWorkingDirectory(".");
        approval.setCommandOptions("timeout=30;longRunning=true");
        approval.setExpiresTime(LocalDateTime.now().plusMinutes(5));
        return approval;
    }

    private StudentProject project() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return project;
    }

    private void waitForFile(Path path, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(Files.exists(path), "Approved command did not start");
    }


}