package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineNativeToolBatchWiringTest {

    @Test
    void nativeRuntimeRoutesStructuredToolBatchesThroughTheDurableBatchExecutor() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("LabexNativeToolBatchExecutor nativeToolBatchExecutor"));
        assertTrue(source.contains("void setNativeToolBatchExecutor"));
        assertTrue(source.contains("processLabexNativeToolBatch("));
        assertTrue(source.contains("AgentRuntimeProfile.LABEX_NATIVE"));
    }
}
