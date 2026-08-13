package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.google.gson.Gson;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentChangeSetMapper;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.labexagent.projectconfig.AgentEffectiveProjectConfigService;
import com.labex.labexagent.projectconfig.AgentRunConfigSnapshotService;
import com.labex.labexagent.run.BackgroundRunWorktreeService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AgentTaskServiceTest {

    @BeforeAll
    static void initializeEntityMetadata() {
        if (TableInfoHelper.getTableInfo(AgentFileChange.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    AgentFileChange.class);
        }
        if (TableInfoHelper.getTableInfo(AgentTask.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    AgentTask.class);
        }
    }

    @Test
    void persistsProviderAndUserVisibleRequestsSeparately() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTaskService service = newTaskService(taskMapper, mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class));
        StudentProject project = new StudentProject();
        project.setProjectId(3);
        LocalDateTime submittedAt = LocalDateTime.of(2026, 8, 4, 20, 15);

        AgentTask task = service.createTask(7, project, "conversation", "session", "build",
                "Expanded provider review prompt", "/review src/App.vue", "src/App.vue", 17,
                false, submittedAt);

        Map<?, ?> payload = new Gson().fromJson(task.getRequestPayload(), Map.class);
        assertEquals("Expanded provider review prompt", payload.get("message"));
        assertEquals("/review src/App.vue", payload.get("displayMessage"));
        assertEquals("/review src/App.vue", task.getTitle());
        assertEquals(submittedAt, task.getSubmittedAt());
        verify(taskMapper).insert(task);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsTheLatestNonTerminalTaskForTheRequestedConversation() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask active = new AgentTask();
        active.setTaskId(72L);
        active.setStatus("running");
        when(taskMapper.selectOne(org.mockito.ArgumentMatchers.any(LambdaQueryWrapper.class))).thenReturn(active);
        AgentTaskService service = newTaskService(taskMapper, mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class));

        AgentTask result = service.findLatestActiveTask(7, 12, "conversation-71");

        assertEquals(active, result);
        ArgumentCaptor<LambdaQueryWrapper<AgentTask>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectOne(queryCaptor.capture());
        AbstractWrapper<AgentTask, ?, ?> query = queryCaptor.getValue();
        query.getSqlSegment();
        assertTrue(query.getParamNameValuePairs().containsValue(7));
        assertTrue(query.getParamNameValuePairs().containsValue(12));
        assertTrue(query.getParamNameValuePairs().containsValue("conversation-71"));
        assertTrue(query.getParamNameValuePairs().containsValue("completed"));
        assertTrue(query.getParamNameValuePairs().containsValue("failed"));
        assertTrue(query.getParamNameValuePairs().containsValue("cancelled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPendingChangesIncludesConflictedChanges() {
        AgentFileChangeMapper fileChangeMapper = mock(AgentFileChangeMapper.class);
        AgentTaskService service = newTaskService(
                mock(AgentTaskMapper.class),
                mock(AgentChangeSetMapper.class),
                fileChangeMapper);

        service.listPendingChanges(7, 12);

        ArgumentCaptor<LambdaQueryWrapper<AgentFileChange>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fileChangeMapper).selectList(queryCaptor.capture());
        AbstractWrapper<AgentFileChange, ?, ?> query = queryCaptor.getValue();
        query.getSqlSegment();

        assertTrue(query.getParamNameValuePairs().containsValue("conflicted"));
    }

    @Test
    void passesOwnershipAndStableIdempotencyThroughToTheTransactionalInteractionClaim() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunExecutionLeaseService executionLeases = mock(AgentRunExecutionLeaseService.class);
        when(executionLeases.instanceId()).thenReturn("instance-a");
        when(executionLeases.leaseDurationMs()).thenReturn(30_000L);
        AgentRunLifecycleService.InteractionClaimOutcome outcome =
                AgentRunLifecycleService.InteractionClaimOutcome.rejected();
        when(lifecycle.claimResolvedInteractionDispatch(
                eq(72L), eq(7), eq(12), eq("interaction-72"), any(), any(), any(), any(), any(Long.class)))
                .thenReturn(outcome);
        AgentTaskService service = new AgentTaskService(
                mock(AgentTaskMapper.class), mock(AgentChangeSetMapper.class), mock(AgentFileChangeMapper.class),
                lifecycle, executionLeases, mock(BackgroundRunWorktreeService.class));

        AgentRunLifecycleService.InteractionClaimOutcome result =
                service.claimResolvedInteractionDispatch(7, 12, 72L, "interaction-72", "Resuming", "Ready");

        assertSame(outcome, result);
        ArgumentCaptor<String> idempotencyKey = ArgumentCaptor.forClass(String.class);
        verify(lifecycle).claimResolvedInteractionDispatch(
                eq(72L), eq(7), eq(12), eq("interaction-72"), eq("Resuming"), eq("Ready"),
                idempotencyKey.capture(), eq("instance-a"), any(Long.class));
        assertEquals(com.labex.labexagent.run.AgentRunTransitionKey.forInteractionResume(72L, "interaction-72"),
                idempotencyKey.getValue());
    }

    @Test
    void persistsTheEpochZeroSnapshotBeforeQueueingTheNewTask() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        when(taskMapper.insert(any(AgentTask.class))).thenAnswer(invocation -> {
            AgentTask inserted = invocation.getArgument(0);
            inserted.setTaskId(71L);
            return 1;
        });
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunConfigSnapshotService snapshotService = mock(AgentRunConfigSnapshotService.class);
        AgentTaskService service = newTaskService(taskMapper, mock(AgentChangeSetMapper.class),
                mock(AgentFileChangeMapper.class), lifecycle);
        service.setRunConfigSnapshotService(snapshotService);
        StudentProject project = new StudentProject();
        project.setProjectId(3);

        service.createTask(7, project, "conversation", "session", "build",
                "message", "display", "src/App.vue", 17, false, LocalDateTime.now());

        InOrder order = inOrder(snapshotService, lifecycle);
        order.verify(snapshotService).createForNewTask(eq(7), eq(project), eq(71L), eq(17), eq("build"));
        order.verify(lifecycle).initialize(any(AgentTask.class), any(), any());
    }

    @Test
    void failsClosedWhenThePreQueueSnapshotCannotBeResolved() {
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunConfigSnapshotService snapshotService = mock(AgentRunConfigSnapshotService.class);
        when(snapshotService.createForNewTask(any(), any(), any(), any(), any()))
                .thenThrow(new AgentEffectiveProjectConfigService.EffectiveConfigException(
                        "config-invalid", List.of()));
        AgentTaskService service = newTaskService(mock(AgentTaskMapper.class),
                mock(AgentChangeSetMapper.class), mock(AgentFileChangeMapper.class), lifecycle);
        service.setRunConfigSnapshotService(snapshotService);
        StudentProject project = new StudentProject();
        project.setProjectId(3);

        assertThrows(AgentEffectiveProjectConfigService.EffectiveConfigException.class,
                () -> service.createTask(7, project, "conversation", "session", "build",
                        "message", "display", "src/App.vue", 17, false, LocalDateTime.now()));
        verify(lifecycle, never()).initialize(any(AgentTask.class), any(), any());
    }

    private AgentTaskService newTaskService(AgentTaskMapper taskMapper,
                                            AgentChangeSetMapper changeSetMapper,
                                            AgentFileChangeMapper fileChangeMapper) {
        return new AgentTaskService(taskMapper, changeSetMapper, fileChangeMapper,
                mock(AgentRunLifecycleService.class), mock(AgentRunExecutionLeaseService.class),
                mock(BackgroundRunWorktreeService.class));
    }

    private AgentTaskService newTaskService(AgentTaskMapper taskMapper,
                                            AgentChangeSetMapper changeSetMapper,
                                            AgentFileChangeMapper fileChangeMapper,
                                            AgentRunLifecycleService lifecycle) {
        return new AgentTaskService(taskMapper, changeSetMapper, fileChangeMapper,
                lifecycle, mock(AgentRunExecutionLeaseService.class),
                mock(BackgroundRunWorktreeService.class));
    }
}
