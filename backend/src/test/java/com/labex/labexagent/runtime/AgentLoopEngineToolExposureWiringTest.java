package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineToolExposureWiringTest {

    @Test
    void nativeRunProjectionUsesTheScopedExposurePlannerAndBindsItToTheRunContext() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"));

        assertTrue(source.contains("ToolExposurePlanner toolExposurePlanner"));
        assertTrue(source.contains("this.planToolExposure(studentId, mode, modelConfig, runtimeProfile, taskId)"));
        assertTrue(source.contains("ctx.setScopedToolBindings(runtimeProjection.toolExposure().scopedTools())"));
        assertTrue(source.contains("ctx.setScopedToolBindings(fresh.toolExposure().scopedTools())"));
    }
}
