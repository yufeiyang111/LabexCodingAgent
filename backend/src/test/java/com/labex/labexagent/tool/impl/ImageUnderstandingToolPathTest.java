package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.multimodal.ConfiguredImageUnderstandingService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageUnderstandingToolPathTest {

    @TempDir
    Path workspace;

    @Test
    void rejectsAbsoluteLocalImagePathsBeforeCallingTheImageService() {
        ConfiguredImageUnderstandingService imageService = mock(ConfiguredImageUnderstandingService.class);
        ImageUnderstandingTool tool = new ImageUnderstandingTool(imageService);
        JsonObject args = new JsonObject();
        args.addProperty("prompt", "read the screenshot");
        args.addProperty("image_url", workspace.getParent().resolve("outside.png").toString());

        ToolResult result = tool.execute(context(), args);

        assertFalse(result.isSuccess());
        verifyNoInteractions(imageService);
    }

    private AgentContext context() {
        StudentProject project = new StudentProject();
        project.setProjectId(12);
        project.setStudentId(7);
        project.setWorkspacePath(workspace.toString());
        return AgentContext.create("session", 7, project, "conversation", 1L);
    }
}
