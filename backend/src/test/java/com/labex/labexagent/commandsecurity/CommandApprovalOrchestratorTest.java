package com.labex.labexagent.commandsecurity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.network.NetworkAccessService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunLeaseHeartbeatService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.AgentVerificationRecorder;
import com.labex.labexagent.run.AgentRunState;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.diff.GitSnapshotService;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.execution.ExecutionStatus;
import com.labex.labexagent.execution.ProcessExecutionIdentity;
import com.labex.labexagent.execution.ProcessExecutionObserver;
import com.labex.labexagent.execution.ProcessExecutionResult;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandApprovalOrchestratorTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path workspace;

    @Test
    void rejectionQueuesTheSameRunForRecoveryBeforeResuming() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        CommandApproval approval = approval("pending");
        CommandApproval rejected = approval("rejected");
        rejected.setDecisionIdempotencyKey("decision-71");
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.decide(7, 12, "approval-71", false, "decision-71")).thenReturn(rejected);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(rejected);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        when(resumeScheduler.resumeIfWaiting(rejected)).thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit,
                mock(AgentApprovedCommandExecutor.class), mock(StudentProjectService.class), lifecycle,
                mock(AgentProjectMetadataRefreshScheduler.class), resumeScheduler, null, toolCalls, transcript,
                mock(AgentCancellationRegistry.class), mock(AgentTaskService.class));

        CommandApprovalOrchestrator.DecisionResult result = orchestrator.decide(
                7, 12, "approval-71", false, "decision-71");

        org.assertj.core.api.Assertions.assertThat(result.available()).isTrue();
        verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_APPROVAL_REJECTED"), any(), any());
        verify(resumeScheduler).resumeIfWaiting(rejected);
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                eq("Command approval was rejected"));
        verify(toolCalls).completedExisting(eq(71L), eq("tool-71"),
                eq("Command approval was rejected"));
    }

    @Test
    void successfulApprovedMutationPublishesWorkspaceChangedBeforeResumingTheAgentLoop() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        CommandApproval approval = approval("approved");
        approval.setCanonicalCommand("rm -rf skills");
        approval.setDisplayCommand("rm -rf skills");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenReturn(new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 12L, "removed skills", false));
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        when(resumeScheduler.resumeIfWaiting(approval)).thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, mock(AgentProjectMetadataRefreshScheduler.class), resumeScheduler, null,
                mock(AgentToolCallJournalService.class), mock(AgentRunTranscriptService.class),
                new AgentCancellationRegistry(), mock(AgentTaskService.class));

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("resuming");
        org.mockito.InOrder eventOrder = org.mockito.Mockito.inOrder(lifecycle, resumeScheduler);
        eventOrder.verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_COMPLETED"), any(), any());
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> workspacePayload = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        eventOrder.verify(lifecycle).appendEvent(eq(71L), eq("WORKSPACE_CHANGED"), workspacePayload.capture(),
                eq("command-lifecycle:v1:approval-71:workspace-changed"));
        org.assertj.core.api.Assertions.assertThat(workspacePayload.getValue())
                .containsEntry("projectId", 12)
                .containsEntry("taskId", 71L)
                .containsEntry("workspaceChangeId", "approval-71:workspace-changed");
        eventOrder.verify(resumeScheduler).resumeIfWaiting(approval);
    }

    @Test
    void consumedApprovalCannotExecuteTheSameCommandOrResumeTheRunTwice() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        CommandApproval approval = approval("approved");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true, false);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenReturn(new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 12L, "deleted skill", false));
        when(resumeScheduler.resumeIfWaiting(approval)).thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, metadataRefresh, resumeScheduler, null,
                mock(AgentToolCallJournalService.class), mock(AgentRunTranscriptService.class),
                new AgentCancellationRegistry(), mock(AgentTaskService.class));

        CommandApprovalOrchestrator.ExecutionResult first = orchestrator.execute(7, 12, "approval-71");
        CommandApprovalOrchestrator.ExecutionResult duplicate = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(first.available()).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.status()).isEqualTo("resuming");
        org.assertj.core.api.Assertions.assertThat(duplicate.available()).isFalse();
        verify(executor, org.mockito.Mockito.times(1))
                .execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class));
        verify(resumeScheduler, org.mockito.Mockito.times(1)).resumeIfWaiting(approval);
        verify(metadataRefresh, org.mockito.Mockito.times(1)).schedule(7, 12, "command_approval");
    }

    @Test
    void approvedDeletionPersistsSnapshotTargetEvidenceBeforeWorkspaceEventAndResume() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        CommandApproval approval = approval("approved");
        approval.setCanonicalCommand("rm -rf skills");
        approval.setDisplayCommand("rm -rf skills");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenReturn(new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 12L, "removed skills", false));
        when(resumeScheduler.resumeIfWaiting(approval)).thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot before = new GitSnapshotService.Snapshot(true, "before-71", "tree", "");
        GitSnapshotService.Snapshot after = new GitSnapshotService.Snapshot(true, "after-71", "tree", "");
        when(snapshots.capture(eq(project), eq("before approved command approval-71"))).thenReturn(before);
        when(snapshots.capture(eq(project), eq("after approved command approval-71"))).thenReturn(after);
        DiffService diffs = mock(DiffService.class);
        PendingChange deleted = new PendingChange("change-71", 7, 12, "conversation-71", 71L, 99L,
                "skills/SKILL.md", "delete", "skill instructions\n", "",
                "diff --git a/skills/SKILL.md b/skills/SKILL.md", "applied");
        when(diffs.recordSnapshotDiffWithoutTaskProjection(7, project, "conversation-71", 71L,
                "command_approval", before, after)).thenReturn(java.util.List.of(deleted));
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, metadataRefresh, resumeScheduler, null,
                mock(AgentToolCallJournalService.class), mock(AgentRunTranscriptService.class),
                new AgentCancellationRegistry(), mock(AgentTaskService.class));
        orchestrator.setWorkspaceChangeEvidenceServices(snapshots, diffs);

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("resuming");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(snapshots, executor, diffs, lifecycle, resumeScheduler);
        order.verify(snapshots).capture(project, "before approved command approval-71");
        order.verify(executor).execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class));
        order.verify(snapshots).capture(project, "after approved command approval-71");
        order.verify(diffs).recordSnapshotDiffWithoutTaskProjection(7, project, "conversation-71", 71L,
                "command_approval", before, after);
        order.verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_COMPLETED"), any(), any());
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> workspacePayload = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        order.verify(lifecycle).appendEvent(eq(71L), eq("WORKSPACE_CHANGED"), workspacePayload.capture(),
                eq("command-lifecycle:v1:approval-71:workspace-changed"));
        org.assertj.core.api.Assertions.assertThat(workspacePayload.getValue())
                .containsEntry("evidenceStatus", "captured")
                .containsEntry("changedFileCount", 1)
                .containsEntry("changedPaths", java.util.List.of("skills/SKILL.md"));
        order.verify(resumeScheduler).resumeIfWaiting(approval);
    }

    @Test
    void approvedDeletionProjectsVerifiedWorkspaceEvidenceIntoTheDeferredToolResult() throws Exception {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        CommandApproval approval = approval("approved");
        approval.setCanonicalCommand("rm -rf skills");
        approval.setDisplayCommand("rm -rf skills");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        Path skill = Files.createDirectories(workspace.resolve("skills")).resolve("SKILL.md");
        Files.writeString(skill, "skill instructions\n");
        AgentTask task = waitingTask();
        task.setConversationId("conversation-71");
        task.setExecutionEpoch(6L);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    Files.delete(skill);
                    Files.delete(workspace.resolve("skills"));
                    return new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 12L, "removed skills", false);
                });
        when(resumeScheduler.resumeIfWaiting(approval)).thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot before = new GitSnapshotService.Snapshot(true, "before-71", "tree", "");
        GitSnapshotService.Snapshot after = new GitSnapshotService.Snapshot(true, "after-71", "tree", "");
        when(snapshots.capture(eq(project), eq("before approved command approval-71"))).thenReturn(before);
        when(snapshots.capture(eq(project), eq("after approved command approval-71"))).thenReturn(after);
        DiffService diffs = mock(DiffService.class);
        PendingChange deleted = new PendingChange("change-71", 7, 12, "conversation-71", 71L, 99L,
                "skills/SKILL.md", "delete", "skill instructions\n", "",
                "diff --git a/skills/SKILL.md b/skills/SKILL.md", "applied");
        when(diffs.recordSnapshotDiffWithoutTaskProjection(7, project, "conversation-71", 71L,
                "command_approval", before, after)).thenReturn(List.of(deleted));
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, metadataRefresh, resumeScheduler, null, toolCalls, transcript,
                new AgentCancellationRegistry(), tasks);
        orchestrator.setWorkspaceChangeEvidenceServices(snapshots, diffs);

        CommandApprovalOrchestrator.ExecutionResult execution = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(execution.status()).isEqualTo("resuming");
        org.mockito.ArgumentCaptor<ToolResult> toolResult = org.mockito.ArgumentCaptor.forClass(ToolResult.class);
        verify(toolCalls).completedExisting(eq(71L), eq("tool-71"), toolResult.capture());
        org.assertj.core.api.Assertions.assertThat(Files.exists(skill)).isFalse();
        org.assertj.core.api.Assertions.assertThat(toolResult.getValue().durableResultMetadata())
                .containsKeys("workspaceIdentity", "workspaceMutation", "workspaceVerification");
        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) toolResult.getValue()
                .durableResultMetadata().get("workspaceVerification");
        org.assertj.core.api.Assertions.assertThat(verification)
                .containsEntry("state", "verified")
                .containsEntry("targets", List.of(Map.of(
                        "path", "skills/SKILL.md",
                        "expectedState", "absent",
                        "observedState", "absent")));
        org.assertj.core.api.Assertions.assertThat(GSON.toJson(toolResult.getValue().durableResultMetadata()))
                .doesNotContain(workspace.toAbsolutePath().normalize().toString());
        org.mockito.ArgumentCaptor<String> transcriptResult = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""), transcriptResult.capture());
        org.assertj.core.api.Assertions.assertThat(transcriptResult.getValue())
                .contains("workspace_verification=verified");
    }

    @Test
    void workspaceChangedBindsDeletionEvidenceToASafeTaskEpochIdentity() throws Exception {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        CommandApproval approval = approval("approved");
        approval.setWorkingDirectory("tools/build");
        Files.createDirectories(workspace.resolve("tools/build"));
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath(workspace.toString());
        AgentTask task = waitingTask();
        task.setConversationId("conversation-71");
        task.setExecutionEpoch(6L);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenReturn(new ProcessExecutionResult(ExecutionStatus.SUCCEEDED, 0, 12L, "removed skill", false));
        when(resumeScheduler.resumeIfWaiting(approval)).thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot before = new GitSnapshotService.Snapshot(true, "before-71", "tree", "");
        GitSnapshotService.Snapshot after = new GitSnapshotService.Snapshot(true, "after-71", "tree", "");
        when(snapshots.capture(eq(project), eq("before approved command approval-71"))).thenReturn(before);
        when(snapshots.capture(eq(project), eq("after approved command approval-71"))).thenReturn(after);
        DiffService diffs = mock(DiffService.class);
        PendingChange deleted = new PendingChange("change-71", 7, 12, "conversation-71", 71L, 99L,
                "skills/SKILL.md", "delete", "skill instructions\n", "",
                "diff --git a/skills/SKILL.md b/skills/SKILL.md", "applied");
        when(diffs.recordSnapshotDiffWithoutTaskProjection(7, project, "conversation-71", 71L,
                "command_approval", before, after)).thenReturn(List.of(deleted));
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals,
                mock(CommandAuditService.class), executor, projects, lifecycle, metadataRefresh, resumeScheduler, null,
                mock(AgentToolCallJournalService.class), mock(AgentRunTranscriptService.class),
                new AgentCancellationRegistry(), tasks);
        orchestrator.setWorkspaceChangeEvidenceServices(snapshots, diffs);

        orchestrator.execute(7, 12, "approval-71");

        org.mockito.ArgumentCaptor<Map<String, Object>> payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(lifecycle).appendEvent(eq(71L), eq("WORKSPACE_CHANGED"), payload.capture(),
                eq("command-lifecycle:v1:approval-71:workspace-changed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) payload.getValue().get("workspaceIdentity");
        org.assertj.core.api.Assertions.assertThat(identity)
                .containsEntry("taskId", 71L)
                .containsEntry("executionEpoch", 6L)
                .containsEntry("workingDirectory", "tools/build")
                .containsEntry("relativePaths", List.of("skills/SKILL.md"));
        org.assertj.core.api.Assertions.assertThat(String.valueOf(identity.get("operationFingerprint"))).hasSize(64);
        org.assertj.core.api.Assertions.assertThat(GSON.toJson(payload.getValue()))
                .doesNotContain(workspace.toAbsolutePath().normalize().toString());
    }

    @Test
    void approvedCommandExecutionResumesTheExistingAgentLoopInsteadOfCompletingTheTask() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService executionLeases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLeaseHeartbeatService leaseHeartbeats = mock(AgentRunLeaseHeartbeatService.class);
        AgentRunExecutionLeaseService.ExecutionLease executionLease =
                new AgentRunExecutionLeaseService.ExecutionLease(
                        71L, "approval-executor-71", 4L, LocalDateTime.now().plusSeconds(30));
        when(executionLeases.acquire(71L)).thenReturn(executionLease);
        CommandApproval approval = approval("approved");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        AgentCancellationRegistry cancellations = spy(new AgentCancellationRegistry());
        AgentTaskService tasks = mock(AgentTaskService.class);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    CancellationToken token = invocation.getArgument(2);
                    AgentCancellationRegistry.CancellationTarget target = cancellations.findCancellationTarget(
                            "session-71", 7, 12);
                    org.assertj.core.api.Assertions.assertThat(target.activeRun()).isSameAs(token);
                    ProcessExecutionObserver observer = invocation.getArgument(3);
                    observer.onStarted(new ProcessExecutionIdentity(
                            "host-71", "executor-71", "local", "task-71", 12345L,
                            1700000000000L, 1700000030000L));
                    return new ProcessExecutionResult(
                            ExecutionStatus.SUCCEEDED, 0, 12L, "tests passed", false);
                });
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        when(resumeScheduler.resumeIfWaiting(approval)).thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, metadataRefresh, resumeScheduler, null, toolCalls, transcript,
                cancellations, tasks);
        orchestrator.setCommandExecutionLeaseServices(executionLeases, leaseHeartbeats);

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("resuming");
        org.mockito.InOrder leaseOrder = org.mockito.Mockito.inOrder(
                executionLeases, leaseHeartbeats, transcript, resumeScheduler);
        leaseOrder.verify(executionLeases).acquire(71L);
        leaseOrder.verify(leaseHeartbeats).track(executionLease, "session-71");
        leaseOrder.verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                org.mockito.ArgumentMatchers.contains("tests passed"));
        leaseOrder.verify(leaseHeartbeats).untrack(executionLease);
        leaseOrder.verify(executionLeases).release(executionLease);
        leaseOrder.verify(resumeScheduler).resumeIfWaiting(approval);
        verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_STARTED"), any(), any());
        verify(audit).recordExecutionStarted(approval);
        verify(audit).recordExecutionProcessBound(eq(approval), any(ProcessExecutionIdentity.class));
        verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_PROCESS_BOUND"), any(), any());
        verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_COMPLETED"), any(), any());
        verify(resumeScheduler).resumeIfWaiting(approval);
        verify(metadataRefresh).schedule(eq(7), eq(12), eq("command_approval"));
        verify(projects, never()).refreshProjectMetadata(eq(7), eq(12));
        verify(lifecycle, never()).transition(eq(71L), eq(AgentRunState.COMPLETED), any(), any(), any(), any(), any());
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                org.mockito.ArgumentMatchers.contains("tests passed"));
        verify(toolCalls).completedExisting(eq(71L), eq("tool-71"),
                org.mockito.ArgumentMatchers.argThat((ToolResult toolResult) -> toolResult != null
                        && toolResult.getContent().contains("tests passed")));
        org.assertj.core.api.Assertions.assertThat(cancellations.findCancellationTarget("session-71", 7, 12).status())
                .isEqualTo(AgentCancellationRegistry.CancellationStatus.NOT_FOUND);
    }

    @Test
    void cancelledApprovedCommandWithPartialMutationPersistsEvidenceAndRefreshesWorkspace() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        CommandApproval approval = approval("approved");
        approval.setCanonicalCommand("rm -rf skills && sleep 10");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(tasks.finalizeCancellation(eq(71L), any(), any())).thenReturn(true);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenReturn(new ProcessExecutionResult(ExecutionStatus.CANCELLED, null, 48L, "cancelled after delete", false));
        GitSnapshotService snapshots = mock(GitSnapshotService.class);
        GitSnapshotService.Snapshot before = mock(GitSnapshotService.Snapshot.class);
        GitSnapshotService.Snapshot after = mock(GitSnapshotService.Snapshot.class);
        when(before.available()).thenReturn(true);
        when(after.available()).thenReturn(true);
        when(snapshots.capture(project, "before approved command approval-71")).thenReturn(before);
        when(snapshots.capture(project, "after approved command approval-71")).thenReturn(after);
        DiffService diffs = mock(DiffService.class);
        PendingChange deleted = new PendingChange("change-71", 7, 12, "conversation-71", 71L, 99L,
                "skills/SKILL.md", "delete", "skill instructions\n", "",
                "diff --git a/skills/SKILL.md b/skills/SKILL.md", "applied");
        when(diffs.recordSnapshotDiffWithoutTaskProjection(7, project, "conversation-71", 71L,
                "command_approval", before, after)).thenReturn(java.util.List.of(deleted));
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, metadataRefresh, resumeScheduler, null, toolCalls, transcript,
                new AgentCancellationRegistry(), tasks);
        orchestrator.setWorkspaceChangeEvidenceServices(snapshots, diffs);

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("cancelled");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(snapshots, executor, diffs, lifecycle, tasks);
        order.verify(snapshots).capture(project, "before approved command approval-71");
        order.verify(executor).execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class));
        order.verify(snapshots).capture(project, "after approved command approval-71");
        order.verify(diffs).recordSnapshotDiffWithoutTaskProjection(7, project, "conversation-71", 71L,
                "command_approval", before, after);
        order.verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_CANCELLED"), any(), any());
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> workspacePayload = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        order.verify(lifecycle).appendEvent(eq(71L), eq("WORKSPACE_CHANGED"), workspacePayload.capture(),
                eq("command-lifecycle:v1:approval-71:workspace-changed"));
        org.assertj.core.api.Assertions.assertThat(workspacePayload.getValue())
                .containsEntry("executionStatus", "cancelled")
                .containsEntry("evidenceStatus", "captured")
                .containsEntry("changedFileCount", 1)
                .containsEntry("changedPaths", java.util.List.of("skills/SKILL.md"));
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                org.mockito.ArgumentMatchers.contains("workspace_evidence=captured"));
        verify(tasks).finalizeCancellation(eq(71L), eq("Cancelled"), any());
        verify(resumeScheduler, never()).resumeIfWaiting(any());
    }

    @Test
    void cancelledApprovedCommandFinalizesTheTaskAndInterruptsTheOriginalToolPart() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentCancellationRegistry cancellations = spy(new AgentCancellationRegistry());
        CommandApproval approval = approval("approved");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(tasks.finalizeCancellation(eq(71L), any(), any())).thenReturn(true);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenAnswer(invocation -> {
                    CancellationToken token = invocation.getArgument(2);
                    org.assertj.core.api.Assertions.assertThat(cancellations.findCancellationTarget(
                            "session-71", 7, 12).activeRun()).isSameAs(token);
                    return new ProcessExecutionResult(ExecutionStatus.CANCELLED, null, 48L, "", false);
                });
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, metadataRefresh, resumeScheduler, null, toolCalls, transcript,
                cancellations, tasks);

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("cancelled");
        verify(audit).recordExecutionInterrupted(approval, "user_cancellation");
        verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_CANCELLED"), any(), any());
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                org.mockito.ArgumentMatchers.contains("status=interrupted"));
        verify(toolCalls).interruptedExisting(eq(71L), eq("tool-71"),
                org.mockito.ArgumentMatchers.contains("status=interrupted"));
        verify(tasks).finalizeCancellation(eq(71L), eq("Cancelled"), any());
        verify(resumeScheduler, never()).resumeIfWaiting(any());
        verify(metadataRefresh).schedule(7, 12, "command_approval_cancelled");
        org.assertj.core.api.Assertions.assertThat(cancellations.findCancellationTarget("session-71", 7, 12).status())
                .isEqualTo(AgentCancellationRegistry.CancellationStatus.NOT_FOUND);
    }

    @Test
    void cancelledApprovedCommandStillFinalizesWhenTranscriptProjectionFails() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentCancellationRegistry cancellations = spy(new AgentCancellationRegistry());
        CommandApproval approval = approval("approved");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(tasks.finalizeCancellation(eq(71L), any(), any())).thenReturn(true);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenReturn(new ProcessExecutionResult(ExecutionStatus.CANCELLED, null, 48L, "", false));
        org.mockito.Mockito.doThrow(new IllegalStateException("transcript unavailable"))
                .when(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""), any());
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, metadataRefresh, resumeScheduler, null, toolCalls, transcript,
                cancellations, tasks);

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.available()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("cancelled");
        verify(toolCalls).interruptedExisting(eq(71L), eq("tool-71"),
                org.mockito.ArgumentMatchers.contains("status=interrupted"));
        verify(lifecycle).appendEvent(eq(71L), eq("COMMAND_EXECUTION_CANCELLED"), any(), any());
        verify(tasks).finalizeCancellation(eq(71L), eq("Cancelled"), any());
        verify(lifecycle, never()).transition(eq(71L), eq(AgentRunState.FAILED), any(), any(), any(), any(), any());
        verify(resumeScheduler, never()).resumeIfWaiting(any());
        verify(metadataRefresh).schedule(7, 12, "command_approval_cancelled");
    }

    @Test
    void doesNotExecuteAnApprovalThatHasBeenSupersededByANewerTaskApproval() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        CommandApproval oldApproval = approval("approved");
        CommandApproval currentApproval = approval("pending");
        currentApproval.setApprovalId("approval-72");
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(oldApproval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(currentApproval);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals,
                mock(CommandAuditService.class), executor, projects, lifecycle,
                mock(AgentProjectMetadataRefreshScheduler.class), mock(CommandApprovalResumeScheduler.class), null,
                mock(AgentToolCallJournalService.class),
                mock(AgentRunTranscriptService.class), mock(AgentCancellationRegistry.class),
                mock(AgentTaskService.class));

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.available()).isFalse();
        verify(approvals).findLatestForTask(7, 12, 71L);
        verify(approvals, never()).consume(any());
        verify(executor, never()).execute(any(), any());
        verify(lifecycle, never()).transition(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotResumeTheTaskWhenAnExpiredApprovalHasBeenSuperseded() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        CommandApproval oldApproval = approval("pending");
        CommandApproval expiredApproval = approval("expired");
        expiredApproval.setDecisionIdempotencyKey("decision-71");
        CommandApproval currentApproval = approval("pending");
        currentApproval.setApprovalId("approval-72");
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(oldApproval);
        when(approvals.decide(7, 12, "approval-71", false, "decision-71")).thenReturn(expiredApproval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(oldApproval, currentApproval);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals,
                mock(CommandAuditService.class), mock(AgentApprovedCommandExecutor.class), mock(StudentProjectService.class),
                lifecycle, mock(AgentProjectMetadataRefreshScheduler.class),
                mock(CommandApprovalResumeScheduler.class), null, mock(AgentToolCallJournalService.class),
                mock(AgentRunTranscriptService.class), mock(AgentCancellationRegistry.class),
                mock(AgentTaskService.class));

        CommandApprovalOrchestrator.DecisionResult result = orchestrator.decide(7, 12, "approval-71", false, "decision-71");

        org.assertj.core.api.Assertions.assertThat(result.available()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.resumeAgentLoop()).isFalse();
        verify(approvals, org.mockito.Mockito.times(2)).findLatestForTask(7, 12, 71L);
        verify(lifecycle, never()).transition(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mavenDependencyFailurePausesForNetworkApprovalWithoutModelReplay() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentCancellationRegistry cancellations = spy(new AgentCancellationRegistry());
        AgentTaskService tasks = mock(AgentTaskService.class);
        CommandApproval approval = approval("approved");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        NetworkAccessService.NetworkAccessRequest request = new NetworkAccessService.NetworkAccessRequest(
                "network-71", "digest-network-71", "mvn test",
                java.util.Map.of("requestKind", "offline_failure_retry"));

        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(approvals.findLatestForTask(7, 12, 71L)).thenReturn(approval);
        when(approvals.consume(any())).thenReturn(true);
        when(executor.execute(eq(approval), eq(project), any(CancellationToken.class), any(ProcessExecutionObserver.class)))
                .thenReturn(new ProcessExecutionResult(ExecutionStatus.FAILED, 1, 120L,
                        "Non-resolvable parent POM: Could not transfer artifact from central", false));
        when(network.beginOfflineCommandRetry(eq(7), eq(12), eq(71L), eq("conversation-71"),
                eq("session-71"), eq("run_tests"), eq("mvn test"), any(), eq("tool-71"),
                eq("tool-71"), eq("approval-71"), any())).thenReturn(request);

        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(approvals, audit, executor,
                projects, lifecycle, metadataRefresh, resumeScheduler, network, toolCalls, transcript,
                cancellations, tasks);

        CommandApprovalOrchestrator.ExecutionResult result = orchestrator.execute(7, 12, "approval-71");

        org.assertj.core.api.Assertions.assertThat(result.available()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo("waiting_network");
        verify(network).beginOfflineCommandRetry(eq(7), eq(12), eq(71L), eq("conversation-71"),
                eq("session-71"), eq("run_tests"), eq("mvn test"), any(), eq("tool-71"),
                eq("tool-71"), eq("approval-71"), any());
        verify(lifecycle).transition(eq(71L), eq(AgentRunState.WAITING_APPROVAL),
                eq("NETWORK_ACCESS_ASK"), any(), any(), any(), any());
        verify(resumeScheduler, never()).resumeIfWaiting(any());
        verify(transcript, never()).appendDeferredToolResult(any(), any(), any(), any());
        verify(toolCalls, never()).completedExisting(any(), any(), org.mockito.ArgumentMatchers.any(String.class));
    }

    @Test
    void approvedOfflineNetworkRetryExecutesPersistedCommandOnceAndResumesOriginalToolCall() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        CommandAuditService audit = mock(CommandAuditService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        StudentProjectService projects = mock(StudentProjectService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentProjectMetadataRefreshScheduler metadataRefresh = mock(AgentProjectMetadataRefreshScheduler.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentCancellationRegistry cancellations = spy(new AgentCancellationRegistry());
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentRunExecutionLeaseService leases = mock(AgentRunExecutionLeaseService.class);
        AgentRunLeaseHeartbeatService heartbeats = mock(AgentRunLeaseHeartbeatService.class);
        AgentVerificationRecorder verifications = mock(AgentVerificationRecorder.class);
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-a", 2L, LocalDateTime.now().plusMinutes(1));

        CommandApproval approval = approval("consumed");
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId("network-71");
        interaction.setTaskId(71L);
        interaction.setStudentId(7);
        interaction.setProjectId(12);
        interaction.setInteractionType("network");
        interaction.setStatus("approved");
        AgentRunInteraction executing = new AgentRunInteraction();
        executing.setInteractionId("network-71");
        executing.setTaskId(71L);
        executing.setStudentId(7);
        executing.setProjectId(12);
        executing.setInteractionType("network");
        executing.setStatus("executing");
        NetworkAccessService.OfflineRetryDescriptor descriptor = new NetworkAccessService.OfflineRetryDescriptor(
                "approval-71", "tool-71", "run_tests", "mvn test", "digest-network-71");
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setStatus("waiting_approval");
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        ProcessExecutionResult processResult = new ProcessExecutionResult(
                ExecutionStatus.SUCCEEDED, 0, 25L, "BUILD SUCCESS", false);

        when(network.offlineRetryDescriptor(interaction)).thenReturn(descriptor);
        when(network.digest("mvn test")).thenReturn("digest-network-71");
        when(network.claimOfflineRetry(interaction)).thenReturn(
                new AgentRunInteractionService.NetworkRetryClaim(executing, true));
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(leases.acquire(71L)).thenReturn(lease);
        when(executor.executeNetworkRetry(eq(approval), eq(project), any(CancellationToken.class),
                any(ProcessExecutionObserver.class))).thenReturn(processResult);
        when(resumeScheduler.resumeIfWaiting(approval)).thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);

        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(
                approvals, audit, executor, projects, lifecycle, metadataRefresh, resumeScheduler,
                network, toolCalls, transcript, cancellations, tasks);
        orchestrator.setCommandExecutionLeaseServices(leases, heartbeats);
        orchestrator.setVerificationRecorder(verifications);

        boolean resumed = orchestrator.resumeOfflineNetworkRetry(interaction);

        org.assertj.core.api.Assertions.assertThat(resumed).isTrue();
        verify(executor).executeNetworkRetry(eq(approval), eq(project), any(CancellationToken.class),
                any(ProcessExecutionObserver.class));
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                org.mockito.ArgumentMatchers.contains("BUILD SUCCESS"));
        verify(toolCalls).completedExisting(eq(71L), eq("tool-71"),
                org.mockito.ArgumentMatchers.argThat((ToolResult toolResult) -> toolResult != null
                        && toolResult.getContent().contains("BUILD SUCCESS")));
        verify(network).completeOfflineRetry(eq(interaction), any());
        verify(leases).release(lease);
        verify(resumeScheduler).resumeIfWaiting(approval);
        verify(lifecycle).appendEvent(eq(71L), eq("NETWORK_RETRY_EXECUTION_STARTED"), any(), any());
        verify(lifecycle).appendEvent(eq(71L), eq("NETWORK_RETRY_EXECUTION_COMPLETED"), any(), any());
        verify(verifications).recordProcessResult(
                eq(71L), eq(7), eq(12), eq("mvn test"), eq("network_retry"), same(processResult));
    }

    @Test
    void rejectedOfflineNetworkRetryResolvesOriginalToolCallWithoutExecution() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        CommandApproval approval = approval("consumed");
        AgentRunInteraction interaction = networkInteraction("rejected");
        AgentTask task = waitingTask();
        NetworkAccessService.OfflineRetryDescriptor descriptor = retryDescriptor();
        when(network.offlineRetryDescriptor(interaction)).thenReturn(descriptor);
        when(network.digest("mvn test")).thenReturn("digest-network-71");
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(resumeScheduler.resumeIfWaiting(approval))
                .thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(
                approvals, mock(CommandAuditService.class), executor, mock(StudentProjectService.class),
                lifecycle, mock(AgentProjectMetadataRefreshScheduler.class), resumeScheduler, network,
                toolCalls, transcript, mock(AgentCancellationRegistry.class), tasks);

        boolean resumed = orchestrator.resumeOfflineNetworkRetry(interaction);

        org.assertj.core.api.Assertions.assertThat(resumed).isTrue();
        verify(executor, never()).executeNetworkRetry(any(), any(), any(), any());
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                org.mockito.ArgumentMatchers.contains("network_approval=rejected"));
        verify(toolCalls).completedExisting(eq(71L), eq("tool-71"),
                org.mockito.ArgumentMatchers.contains("network_approval=rejected"));
        verify(resumeScheduler).resumeIfWaiting(approval);
    }

    @Test
    void consumedOfflineNetworkRetryOnlyResumesAndNeverExecutesAgain() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentApprovedCommandExecutor executor = mock(AgentApprovedCommandExecutor.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        CommandApproval approval = approval("consumed");
        AgentRunInteraction interaction = networkInteraction("approved");
        AgentRunInteraction consumed = networkInteraction("consumed");
        when(network.offlineRetryDescriptor(interaction)).thenReturn(retryDescriptor());
        when(network.digest("mvn test")).thenReturn("digest-network-71");
        when(network.claimOfflineRetry(interaction)).thenReturn(
                new AgentRunInteractionService.NetworkRetryClaim(consumed, false));
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(waitingTask());
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        StudentProjectService projects = mock(StudentProjectService.class);
        when(projects.getOwnedProject(7, 12)).thenReturn(project);
        when(resumeScheduler.resumeIfWaiting(approval))
                .thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(
                approvals, mock(CommandAuditService.class), executor, projects,
                mock(AgentRunLifecycleService.class), mock(AgentProjectMetadataRefreshScheduler.class),
                resumeScheduler, network, mock(AgentToolCallJournalService.class),
                mock(AgentRunTranscriptService.class), mock(AgentCancellationRegistry.class), tasks);

        boolean resumed = orchestrator.resumeOfflineNetworkRetry(interaction);

        org.assertj.core.api.Assertions.assertThat(resumed).isTrue();
        verify(executor, never()).executeNetworkRetry(any(), any(), any(), any());
        verify(resumeScheduler).resumeIfWaiting(approval);
    }

    @Test
    void protocolMismatchFailsThroughTheLifecycleOwner() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        AgentRunInteraction interaction = networkInteraction("approved");
        when(network.offlineRetryDescriptor(interaction)).thenReturn(retryDescriptor());
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(null);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(
                approvals, mock(CommandAuditService.class), mock(AgentApprovedCommandExecutor.class),
                mock(StudentProjectService.class), lifecycle, mock(AgentProjectMetadataRefreshScheduler.class),
                mock(CommandApprovalResumeScheduler.class), network, mock(AgentToolCallJournalService.class),
                mock(AgentRunTranscriptService.class), mock(AgentCancellationRegistry.class),
                mock(AgentTaskService.class));

        boolean resumed = orchestrator.resumeOfflineNetworkRetry(interaction);

        org.assertj.core.api.Assertions.assertThat(resumed).isFalse();
        verify(lifecycle).transition(eq(71L), eq(AgentRunState.FAILED),
                eq("NETWORK_RETRY_PROTOCOL_FAILED"), any(), any(), any(), any());
    }

    @Test
    void canResumeOfflineNetworkRetryOnlyWhenACommandApprovalMatches() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        CommandApproval approval = approval("consumed");
        AgentRunInteraction interaction = networkInteraction("approved");
        when(network.offlineRetryDescriptor(interaction)).thenReturn(retryDescriptor());
        when(network.digest("mvn test")).thenReturn("digest-network-71");
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(
                approvals, mock(CommandAuditService.class), mock(AgentApprovedCommandExecutor.class),
                mock(StudentProjectService.class), mock(AgentRunLifecycleService.class),
                mock(AgentProjectMetadataRefreshScheduler.class), mock(CommandApprovalResumeScheduler.class),
                network, mock(AgentToolCallJournalService.class), mock(AgentRunTranscriptService.class),
                mock(AgentCancellationRegistry.class), mock(AgentTaskService.class));

        org.assertj.core.api.Assertions.assertThat(orchestrator.canResumeOfflineNetworkRetry(interaction)).isTrue();
    }

    @Test
    void canResumeOfflineNetworkRetryIsReadOnlyAndFalseWithoutAnApproval() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        AgentRunInteraction interaction = networkInteraction("approved");
        when(network.offlineRetryDescriptor(interaction)).thenReturn(retryDescriptor());
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(null);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(
                approvals, mock(CommandAuditService.class), mock(AgentApprovedCommandExecutor.class),
                mock(StudentProjectService.class), lifecycle, mock(AgentProjectMetadataRefreshScheduler.class),
                mock(CommandApprovalResumeScheduler.class), network, mock(AgentToolCallJournalService.class),
                mock(AgentRunTranscriptService.class), mock(AgentCancellationRegistry.class),
                mock(AgentTaskService.class));

        org.assertj.core.api.Assertions.assertThat(orchestrator.canResumeOfflineNetworkRetry(interaction)).isFalse();
        verify(lifecycle, never()).transition(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void timedOutOfflineNetworkRetryResolvesTheToolCallWithoutExecution() {
        CommandApprovalService approvals = mock(CommandApprovalService.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        CommandApprovalResumeScheduler resumeScheduler = mock(CommandApprovalResumeScheduler.class);
        NetworkAccessService network = mock(NetworkAccessService.class);
        AgentToolCallJournalService toolCalls = mock(AgentToolCallJournalService.class);
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskService tasks = mock(AgentTaskService.class);
        CommandApproval approval = approval("consumed");
        AgentRunInteraction interaction = networkInteraction("timed_out");
        AgentTask task = waitingTask();
        when(network.offlineRetryDescriptor(interaction)).thenReturn(retryDescriptor());
        when(network.digest("mvn test")).thenReturn("digest-network-71");
        when(approvals.findOwned(7, 12, "approval-71")).thenReturn(approval);
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task);
        when(resumeScheduler.resumeIfWaiting(approval))
                .thenReturn(CommandApprovalResumeScheduler.ResumeResult.RESUMED);
        CommandApprovalOrchestrator orchestrator = new CommandApprovalOrchestrator(
                approvals, mock(CommandAuditService.class), mock(AgentApprovedCommandExecutor.class),
                mock(StudentProjectService.class), lifecycle, mock(AgentProjectMetadataRefreshScheduler.class),
                resumeScheduler, network, toolCalls, transcript, mock(AgentCancellationRegistry.class), tasks);

        boolean resumed = orchestrator.resumeOfflineNetworkRetry(interaction);

        org.assertj.core.api.Assertions.assertThat(resumed).isTrue();
        verify(transcript).appendDeferredToolResult(eq(71L), eq("tool-71"), eq(""),
                org.mockito.ArgumentMatchers.contains("network_approval=timed_out"));
        verify(toolCalls).completedExisting(eq(71L), eq("tool-71"),
                org.mockito.ArgumentMatchers.contains("network_approval=timed_out"));
        verify(resumeScheduler).resumeIfWaiting(approval);
    }

    private AgentRunInteraction networkInteraction(String status) {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId("network-71");
        interaction.setTaskId(71L);
        interaction.setStudentId(7);
        interaction.setProjectId(12);
        interaction.setInteractionType("network");
        interaction.setStatus(status);
        return interaction;
    }

    private AgentTask waitingTask() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setStatus("waiting_approval");
        return task;
    }

    private NetworkAccessService.OfflineRetryDescriptor retryDescriptor() {
        return new NetworkAccessService.OfflineRetryDescriptor(
                "approval-71", "tool-71", "run_tests", "mvn test", "digest-network-71");
    }

    private CommandApproval approval(String status) {
        CommandApproval approval = new CommandApproval();
        approval.setApprovalId("approval-71");
        approval.setStudentId(7);
        approval.setProjectId(12);
        approval.setTaskId(71L);
        approval.setConversationId("conversation-71");
        approval.setSessionId("session-71");
        approval.setSource("agent_shell");
        approval.setInvocationId("invoke-71");
        approval.setToolCallId("tool-71");
        approval.setCommandDigest("digest-71");
        approval.setCanonicalCommand("mvn test");
        approval.setDisplayCommand("mvn test");
        approval.setWorkingDirectory(".");
        approval.setShell("direct");
        approval.setCommandOptions("timeout=120");
        approval.setClassification("REQUIRE_APPROVAL");
        approval.setPolicyVersion("policy-v1");
        approval.setStatus(status);
        approval.setExpiresTime(LocalDateTime.now().plusMinutes(10));
        return approval;
    }
}
