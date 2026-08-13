package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.CreateProposalRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalResult;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentToolTurnExecutor;
import com.labex.labexagent.tool.ToolResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProposeProjectConfigToolTest {

    private static final String INTERACTION_ID = "interaction-config-1";
    private static final Long PROPOSAL_ID = 5L;
    private static final String DIGEST = "abc123digest";
    private static final String PATHS = "agents/main.json, environment.json";
    private static final LocalDateTime EXPIRY = LocalDateTime.of(2026, 8, 12, 12, 0);

    private final AgentProjectConfigProposalService proposalService = mock(AgentProjectConfigProposalService.class);
    private final AgentRunInteractionService interactionService = mock(AgentRunInteractionService.class);
    private final AgentRunLifecycleService lifecycleService = mock(AgentRunLifecycleService.class);
    private final ProposeProjectConfigTool tool =
            new ProposeProjectConfigTool(proposalService, interactionService, lifecycleService);

    @AfterEach
    void clearToolCallBinding() {
        AgentToolTurnExecutor.clearCurrentToolCallId();
    }

    @Test
    void createsProposalAndWaitingInteractionWithContextProvenanceAndReturnsBlockingResult() throws Exception {
        AgentContext context = context();
        AgentToolTurnExecutor.bindCurrentToolCallId("call-123");
        ProposalResult proposal = new ProposalResult(PROPOSAL_ID, "pending", 1L, DIGEST, PATHS, EXPIRY);
        when(proposalService.createProposal(eq(7), eq(12), any())).thenReturn(proposal);
        AgentRunInteraction waiting = interaction(proposal);
        when(interactionService.createWaiting(any())).thenReturn(waiting);
        when(lifecycleService.appendEvent(any(ExecutionFence.class), eq(71L), eq("CONFIG_PROPOSAL_CREATED"),
                any(), any())).thenReturn(null);

        ToolResult result = tool.execute(context, arguments("candidate-content-here"));

        assertThat(result.isInteractionRequired()).isTrue();
        assertThat(result.getInteractionType()).isEqualTo("config_proposal");
        assertThat(result.getInteractionRequestId()).isEqualTo(INTERACTION_ID);
        verify(proposalService).createProposal(eq(7), eq(12), argThat(request -> {
            assertThat(request.originTaskId()).isEqualTo(71L);
            assertThat(request.originExecutionEpoch()).isEqualTo(4L);
            assertThat(request.originToolCallId()).isEqualTo("call-123");
            assertThat(request.source()).isEqualTo(AgentProjectConfigProposalService.SOURCE_AGENT);
            assertThat(request.candidate()).containsEntry("agent.json", "candidate-content-here");
            assertThat(request.reason()).isNotBlank();
            return true;
        }));
        verify(interactionService).createWaiting(argThat(candidate -> {
            assertThat(candidate.interactionType()).isEqualTo("config_proposal");
            assertThat(candidate.taskId()).isEqualTo(71L);
            assertThat(candidate.studentId()).isEqualTo(7);
            assertThat(candidate.projectId()).isEqualTo(12);
            assertThat(candidate.idempotencyKey()).isNotBlank();
            assertThat(candidate.requestPayload()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) candidate.requestPayload();
            assertThat(payload.get("proposalId")).isEqualTo(PROPOSAL_ID);
            assertThat(payload.get("candidateConfigDigest")).isEqualTo(DIGEST);
            assertThat(payload.get("changedPathSummary")).isEqualTo(PATHS);
            assertThat(payload.get("expiresTime")).isEqualTo(EXPIRY.toString());
            assertThat(payload.get("toolCallId")).isEqualTo("call-123");
            assertThat(String.valueOf(payload)).doesNotContain("candidate-content-here");
            assertThat(String.valueOf(payload)).doesNotContain("secret");
            return true;
        }));
        verify(lifecycleService).appendEvent(any(ExecutionFence.class), eq(71L), eq("CONFIG_PROPOSAL_CREATED"),
                argThat(payload -> {
                    assertThat(payload).isInstanceOf(Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) payload;
                    assertThat(map.get("proposalId")).isEqualTo(PROPOSAL_ID);
                    assertThat(map.get("candidateConfigDigest")).isEqualTo(DIGEST);
                    assertThat(map.get("changedPathSummary")).isEqualTo(PATHS);
                    assertThat(map.get("taskId")).isEqualTo(71L);
                    assertThat(map.get("executionEpoch")).isEqualTo(4L);
                    assertThat(String.valueOf(map)).doesNotContain("candidate-content-here");
                    return true;
                }), argThat(key -> String.valueOf(key).startsWith("config-proposal-created")));
    }

    @Test
    void userInputCanNeverOverrideTaskEpochOrToolCallProvenance() throws Exception {
        AgentContext context = context();
        AgentToolTurnExecutor.bindCurrentToolCallId("call-prov");
        when(proposalService.createProposal(eq(7), eq(12), any()))
                .thenReturn(new ProposalResult(PROPOSAL_ID, "pending", 1L, DIGEST, PATHS, EXPIRY));
        when(interactionService.createWaiting(any())).thenReturn(interaction(
                new ProposalResult(PROPOSAL_ID, "pending", 1L, DIGEST, PATHS, EXPIRY)));

        JsonObject spoofed = arguments("spoofed");
        spoofed.addProperty("taskId", 9999L);
        spoofed.addProperty("executionEpoch", 99L);
        spoofed.addProperty("toolCallId", "user-controlled");
        tool.execute(context, spoofed);

        verify(proposalService).createProposal(eq(7), eq(12), argThat(request -> {
            assertThat(request.originTaskId()).isEqualTo(71L);
            assertThat(request.originExecutionEpoch()).isEqualTo(4L);
            assertThat(request.originToolCallId()).isEqualTo("call-prov");
            return true;
        }));
        verify(interactionService).createWaiting(argThat(candidate -> {
            assertThat(candidate.requestPayload()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) candidate.requestPayload();
            assertThat(payload.get("toolCallId")).isEqualTo("call-prov");
            return true;
        }));
    }

    @Test
    void rejectsExecutionWithoutActiveFenceBeforeTouchingAnyService() {
        AgentContext context = context();
        context.setExecutionFence(null);

        assertThatThrownBy(() -> tool.execute(context, arguments("candidate")))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);
        verify(proposalService, never()).createProposal(any(), any(), any());
        verify(interactionService, never()).createWaiting(any());
        verify(lifecycleService, never()).appendEvent(any(), any(), any(), any(), any());
    }

    @Test
    void replayReturnsTheFirstDurableProposalAndInteraction() throws Exception {
        AgentContext context = context();
        AgentToolTurnExecutor.bindCurrentToolCallId("call-replay");
        ProposalResult proposal = new ProposalResult(PROPOSAL_ID, "pending", 1L, DIGEST, PATHS, EXPIRY);
        AgentRunInteraction waiting = interaction(proposal);
        when(proposalService.createProposal(eq(7), eq(12), any())).thenReturn(proposal);
        when(interactionService.createWaiting(any())).thenReturn(waiting);
        when(lifecycleService.appendEvent(any(ExecutionFence.class), eq(71L), eq("CONFIG_PROPOSAL_CREATED"),
                any(), any())).thenReturn(null);

        ToolResult first = tool.execute(context, arguments("candidate"));
        ToolResult replay = tool.execute(context, arguments("candidate"));

        assertThat(first.getInteractionRequestId()).isEqualTo(INTERACTION_ID);
        assertThat(replay.getInteractionRequestId()).isEqualTo(INTERACTION_ID);
        assertThat(replay.getInteractionType()).isEqualTo("config_proposal");
        verify(interactionService, org.mockito.Mockito.times(2)).createWaiting(argThat(candidate ->
                "config-proposal:v1:71:5".equals(candidate.idempotencyKey())));
    }

    @Test
    void definitionExposesOnlyCandidateExpectedRevisionAndReasonWithoutApplyOrSecretCapability() {
        Map<String, Object> schema = tool.definition().getInputSchema();
        assertThat(tool.definition().getName()).isEqualTo("propose_project_config");
        assertThat(schema).containsKey("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder("candidate", "expectedRevision", "reason");
        assertThat(String.valueOf(schema)).doesNotContain("apply");
        assertThat(String.valueOf(schema)).doesNotContain("secret");
        assertThat(String.valueOf(schema)).doesNotContain("maintenance");
    }

    private AgentRunInteraction interaction(ProposalResult proposal) {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId(INTERACTION_ID);
        interaction.setTaskId(71L);
        interaction.setStudentId(7);
        interaction.setProjectId(12);
        interaction.setInteractionType("config_proposal");
        interaction.setStatus("waiting");
        interaction.setIdempotencyKey("config-proposal:v1:71:" + proposal.proposalId());
        interaction.setExpiresTime(proposal.expiresTime());
        return interaction;
    }

    private JsonObject arguments(String candidateContent) {
        JsonObject args = new JsonObject();
        JsonObject candidate = new JsonObject();
        candidate.addProperty("agent.json", candidateContent);
        args.add("candidate", candidate);
        args.addProperty("expectedRevision", 1L);
        args.addProperty("reason", "update main agent");
        return args;
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setWorkspacePath("C:/workspaces/project-12");
        AgentContext context = new AgentContext("session-1", 7, project, "conversation-1", 71L,
                java.nio.file.Path.of("C:/workspaces/project-12"), List.of(), 0);
        context.setMode("build");
        context.setExecutionEpoch(4L);
        context.setExecutionFence(new ExecutionFence(71L, "instance-a", 4L));
        return context;
    }
}

