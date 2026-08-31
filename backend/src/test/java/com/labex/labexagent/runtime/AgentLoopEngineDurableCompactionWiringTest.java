package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineDurableCompactionWiringTest {

    @Test
    void removesTheRetiredMutableProviderTranscriptFromTheRunLoop() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"));

        assertFalse(source.contains("List<Map<String, Object>> msgs = new ArrayList<>()"));
        assertFalse(source.contains("CompactionSelection.select(msgs"));
        assertFalse(source.contains("estimateProviderRequestTokens(sysPrompt, tools, msgs"));
        assertFalse(source.contains("streamFinalFromProvider("));
        assertFalse(source.contains("replaceProviderProjection("));
        assertTrue(source.contains("selectDurableCompaction("));
    }

    @Test
    void restoresFromCompactionAwareProjectionAndAppendsOnlyDurableFacts() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"));

        assertTrue(source.contains("AgentTranscriptProjectionService durableProjector = this.requireTranscriptProjectionService()"));
        assertTrue(source.contains("durableProjector.loadDurableProjection(task.getTaskId())"));
        assertTrue(source.contains("void setProviderTranscriptAppender"));
        assertTrue(source.contains("private void appendProviderMessage("));
        assertTrue(source.contains("this.requireProviderTranscriptAppender().append(executionFence"));
        assertFalse(source.contains("TranscriptMessageList"));
        assertTrue(source.contains("compactionService.start("));
        assertTrue(source.contains("compactionService.complete("));
        assertTrue(source.contains("compactionService.fail("));
        assertTrue(source.contains("requestTokenEstimator.estimate("));
        assertTrue(source.contains("ContextOverflowRecoveryPolicy"));
        assertFalse(source.contains("this.compactionService == null ?"));
        assertFalse(source.contains("this.compactionService != null"));
        assertFalse(source.contains("this.transcriptService == null || taskId == null"));
        assertFalse(source.contains("this.transcriptProjectionService != null"));
        assertFalse(source.contains("this.compactionAgent == null"));
    }

    @Test
    void compactionSelectionReadsTheUnhydratedViewSoSelectionPersistenceNeverCarriesBase64() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"));

        // 选材结果会整体持久化进 t_agent_compaction_record；
        // 必须读取未注水的 durable 视图，Base64 只允许出现在 Provider 请求边界。
        assertTrue(source.contains("loadDurableCompactionView(taskId)"));
    }
}
