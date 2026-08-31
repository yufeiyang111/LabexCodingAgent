package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineWiringContractTest {

    @Test
    void legacyConstructorsMustNotCreateAnUnwiredRuntime() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        assertEquals(1, count(source, "public AgentLoopEngine("));
        assertFalse(source.contains("interactionService, null, null, null, null, null"));
    }

    @Test
    void coreAgentLoopDependenciesMustNotBeOptionalInSpringRuntime() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        // 核心运行时依赖必须 required；可选功能服务允许 optional，但必须是本清单内的已知项，
        // 防止未来悄悄塞入新的 optional 依赖（新依赖要么 required 要么先更新本契约）。
        java.util.List<String> allowedOptionalSetters = java.util.List.of(
                "void setRunPlanService(AgentRunPlanService runPlanService)",
                "void setSubagentCompletions(com.labex.labexagent.run.SubagentCompletionRegistry subagentCompletions)",
                "void setSubagentRuntimeService(com.labex.labexagent.run.AgentSubagentService subagentRuntimeService)",
                "void setSubagentProperties(com.labex.labexagent.run.AgentSubagentProperties subagentProperties)");
        int optionalAutowired = source.indexOf("@Autowired(required = false)");
        assertTrue(optionalAutowired >= 0);
        assertTrue(source.indexOf("void setRunPlanService(AgentRunPlanService runPlanService)") > optionalAutowired);
        for (String setter : allowedOptionalSetters) {
            assertTrue(source.contains(setter), "missing known optional setter: " + setter);
        }
        assertEquals(allowedOptionalSetters.size(), count(source, "@Autowired(required = false)"),
                "unexpected @Autowired(required = false) count; extend the allowlist deliberately");
        assertTrue(source.contains("workspaceInstructionService == null"));
        assertTrue(source.contains("this.runPlanService == null"));
        assertTrue(source.contains("void setExecutionLeaseServices"));
        assertTrue(source.contains("void setRunFinalizer"));
        assertTrue(source.contains("void setArtifactService"));
        assertTrue(source.contains("void setToolCallJournalService"));
        assertTrue(source.contains("void setRunInteractionService"));
        assertTrue(source.contains("void setTranscriptService"));
        assertTrue(source.contains("void setTranscriptProjectionService"));
        assertFalse(source.contains("runInteractionService != null"));
        assertTrue(source.contains("this.transcriptService = requireRuntimeDependency"));
        assertTrue(source.contains("this.transcriptProjectionService = requireRuntimeDependency"));
        assertTrue(source.contains("this.runInteractionService = requireRuntimeDependency"));
        assertFalse(source.contains("runFinalizer == null"));
        assertFalse(source.contains("artifactService == null || context"));
        assertFalse(source.contains("toolCallJournalService == null"));
        assertFalse(source.contains("toolCallJournalService != null"));
        assertTrue(source.contains("void setContextCompactionServices"));
        assertTrue(source.contains("void setProjectCheckoutLeaseServices"));
    }
    @Test
    void executorWritesMustCarryTheSingleLeaseDerivedExecutionFence() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        // The fence is created exactly once, from the acquired execution lease — never from
        // request JSON or a stale AgentTask object.
        assertTrue(source.contains("new ExecutionFence(task.getTaskId(), executionLease.owner(), executionLease.epoch())"));
        assertTrue(source.contains("ctx.setExecutionFence(executionFence)"));
        assertFalse(source.contains("new ExecutionFence(request"));
        assertFalse(source.contains("new ExecutionFence(task.getExecutionEpoch"));

        // Provider transcript 统一经唯一追加器转发同一个 fence；Engine 不得保留第二套序号/写入逻辑。
        assertTrue(source.contains("void setProviderTranscriptAppender"));
        assertTrue(source.contains("this.providerTranscriptAppender = requireProcessor"));
        assertTrue(source.contains("appendProviderMessage(ExecutionFence executionFence"));
        assertTrue(source.contains("this.requireProviderTranscriptAppender().append(executionFence"));
        assertTrue(source.contains("executionFence, task.getTaskId(), studentId, projectId,"));
        assertTrue(source.contains("toolTurnExecutor.execute(t, ctx, args, name, toolCallId)"));
    }
    @Test
    void staleFenceFailureStopsTheTurnWithoutPartialBatchProjection() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        // 单调 stale fence 下任何后续 durable 写都会再次失败：batch catch 必须直接 rethrow，
        // 剩余 batch 的 Part 留给恢复/接管路径（reconciler + interruptOpenParts），不写 skipped 投影。
        assertFalse(source.contains("lost its execution fence"));
        assertTrue(count(source, "throw staleFence;") >= 2);

        // 外层处理器对 stale fence 只投影安全失败，不写 terminal task state。
        assertTrue(source.contains("else if (e instanceof AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence)"));
    }

    @Test
    void staleFenceBranchProjectsOnlyTransientSseWithoutDurableEvents() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);
        String marker = "else if (e instanceof AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {";
        int start = source.indexOf(marker);
        assertTrue(start >= 0);
        int end = source.indexOf("} else {", start);
        String branch = source.substring(start, end);

        // 失去租约的 worker 只能瞬时投影：不得调用 durable sendEvent/streamFinal，
        // 可恢复任务不得发送 durable FINAL/DONE；durable 事实由 takeover 路径写入。
        assertTrue(branch.contains("sendTransient("));
        assertFalse(branch.contains("sendEvent(sse"));
        assertFalse(branch.contains("streamFinal("));
        assertTrue(branch.contains("takeover"));
    }

    private int count(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
