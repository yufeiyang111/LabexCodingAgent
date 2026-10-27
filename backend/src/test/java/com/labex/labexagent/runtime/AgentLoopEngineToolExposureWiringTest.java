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
        // 工作区根必须一起传下去：项目级 .skill/ 技能是判断 skill 工具可用性的依据之一，
        // 少了它，刚放进去的项目技能会因为账号没有用户级技能而整个不可见。
        assertTrue(source.contains(
                "this.planToolExposure(studentId, mode, modelConfig, runtimeProfile, taskId," + System.lineSeparator()
                        + "                ProjectWorkspace.workspaceRootOrNull(project))"));
        assertTrue(source.contains("ctx.setScopedToolBindings(runtimeProjection.toolExposure().scopedTools())"));
        assertTrue(source.contains("ctx.setScopedToolBindings(fresh.toolExposure().scopedTools())"));
    }
}
