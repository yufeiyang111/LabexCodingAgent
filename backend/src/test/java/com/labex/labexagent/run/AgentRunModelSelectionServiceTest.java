package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.secret.SecretStore;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.impl.PlanExitTool;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.AgentModelConfigService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * 恢复必须保留任务当初选定的精确模型与模式，
 * 不能因为配置被删除或用户默认值变化而静默 fallback。
 */
class AgentRunModelSelectionServiceTest {

    @Test
    void continuationRequestCarriesTheExactPersistedModelIntoResume() {
        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task(), "continue");

        assertThat(request.getResumeTaskId()).isEqualTo(71L);
        assertThat(request.getModelConfigId()).isEqualTo(42);
        assertThat(request.getMode()).isEqualTo("build");
    }

    @Test
    void resumedTaskRetainsItsExactModelWithoutSilentFallback() {
        AgentModelConfigService models = spy(new AgentModelConfigService(mock(SecretStore.class)));
        doReturn(null).when(models).getOwned(7, 42);
        doReturn(config(9, "current-default-model")).when(models).getDefault(7);
        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task(), "continue");

        // SECURE TARGET: 恢复的任务必须保留原模型或 fail closed，不能静默切到当前默认模型
        AgentModelConfig resolved = models.resolveForStudent(7, request.getModelConfigId());

        assertThat(resolved).isNull();
    }

    @Test
    void explicitlyResolvedOwnedModelIsUsedOverTheDefault() {
        AgentModelConfigService models = spy(new AgentModelConfigService(mock(SecretStore.class)));
        doReturn(config(42, "resumed-exact-model")).when(models).getOwned(7, 42);
        doReturn(config(9, "current-default-model")).when(models).getDefault(7);

        AgentModelConfig resolved = models.resolveForStudent(7, 42);

        assertThat(resolved.getConfigId()).isEqualTo(42);
        assertThat(resolved.getModelName()).isEqualTo("resumed-exact-model");
    }

    @Test
    void planExitPersistsTheModeSoRestartKeepsBuildMode() throws Exception {
        AgentContext context = new AgentContext("session-1", 7, new StudentProject(),
                "conversation-1", 71L, Path.of("."), new ArrayList<>(), 0);
        context.setMode("plan");
        context.setExecutionFence(new ExecutionFence(71L, "instance-a", 4L));
        AgentTask task = task();
        task.setMode("plan");
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(tasks.selectById(71L)).thenReturn(task);
        when(tasks.update(any(), any())).thenReturn(1);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.hasEvent(71L, AgentRunModeService.planToBuildKey(71L))).thenReturn(false);
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(901L);
        event.setEventType("RUN_MODE_CHANGED");
        event.setIdempotencyKey(AgentRunModeService.planToBuildKey(71L));
        when(lifecycle.appendEvent(any(ExecutionFence.class), org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq("RUN_MODE_CHANGED"), any(), org.mockito.ArgumentMatchers.eq(
                        AgentRunModeService.planToBuildKey(71L)))).thenReturn(event);
        AgentRunModeService modeService = new AgentRunModeService(tasks, lifecycle,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
        JsonObject args = new JsonObject();
        args.addProperty("plan_summary", "Implement the reviewed plan");

        ToolResult result = new PlanExitTool(modeService).execute(context, args);

        assertThat(result.isSuccess()).isTrue();
        assertThat(context.getMode()).isEqualTo("build");
        // SECURE TARGET: plan_exit 必须经 fenced CAS 持久化模式，重启恢复后仍保持 build 模式
        assertThat(task.getMode()).isEqualTo("build");
    }

    @Test
    void typedConfigurationFailureCarriesSafeStableReasonCodes() {
        AgentRunConfigurationException missing = new AgentRunConfigurationException(
                AgentRunConfigurationException.Reason.MODEL_CONFIG_MISSING);
        assertThat(missing.reason()).isEqualTo(AgentRunConfigurationException.Reason.MODEL_CONFIG_MISSING);
        assertThat(missing.getMessage()).contains("model-config-missing");
        assertThat(new AgentRunConfigurationException(
                AgentRunConfigurationException.Reason.MODEL_CONFIG_DISABLED).getMessage())
                .contains("model-config-disabled");
        assertThat(new AgentRunConfigurationException(
                AgentRunConfigurationException.Reason.MODEL_CONFIG_NOT_PERSISTED).getMessage())
                .contains("model-config-not-persisted");
        // 结构化失败不回显配置原文或秘密
        assertThat(new AgentRunConfigurationException(
                AgentRunConfigurationException.Reason.MODEL_CONFIG_MISSING, "configId=42").getMessage())
                .doesNotContain("apiKey")
                .doesNotContain("secret");
    }

    @Test
    void resumeConfigurationFailureIsTerminalBeforeTheUnboundTransientProjection() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");

        // 配置失败分支必须先于 unbound 瞬时投影：任务不能永远停留在可恢复等待态被调度器重复投递。
        int configBranch = source.indexOf("e instanceof AgentRunConfigurationException configurationFailure");
        int unboundBranch = source.indexOf("this.reportUnboundDispatchFailure(sse, task, visibleLanguage, e)");
        assertTrue(configBranch >= 0, "AgentRunConfigurationException catch branch must exist");
        assertTrue(unboundBranch >= 0, "reportUnboundDispatchFailure call must exist");
        assertTrue(configBranch < unboundBranch,
                "configuration failure must be handled before the unbound transient projection");
        assertTrue(source.contains("this.failTaskForConfiguration(sse, task, project, runLog, visibleLanguage, executionLease,"),
                "configuration failure handler must be wired");
        // 终态迁移必须经 lifecycle 权威并携带 reason code；FAILED 终态让调度器停止重投递。
        assertTrue(source.contains("this.runLifecycleService.transition"),
                "terminal transition must go through the lifecycle authority");
        assertTrue(source.contains("\"RUN_CONFIGURATION_FAILED\""),
                "terminal configuration failure event type must be durable");
        assertTrue(source.contains("payload.put(\"reasonCode\", reasonCode)"),
                "reason code must be part of the durable event payload");
        assertTrue(source.contains("AgentRunState.FAILED"),
                "configuration failure must converge to the terminal FAILED state");
        assertFalse(source.contains("reportUnboundDispatchFailure(sse, task, visibleLanguage, configurationFailure)"),
                "configuration failure must not degrade to the unbound transient projection");
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-1");
        task.setSessionId("session-1");
        task.setMode("build");
        task.setStatus("running");
        task.setRequestPayload("""
                {"studentId":7,"projectId":12,"conversationId":"conversation-1","sessionId":"session-1",
                 "mode":"build","message":"hello","displayMessage":"hello","activePath":"","modelConfigId":42}
                """);
        return task;
    }

    private AgentModelConfig config(int configId, String modelName) {
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(configId);
        config.setStudentId(7);
        config.setModelName(modelName);
        config.setProvider("openai_compatible");
        config.setStatus(1);
        config.setIsDefault(0);
        return config;
    }
}
