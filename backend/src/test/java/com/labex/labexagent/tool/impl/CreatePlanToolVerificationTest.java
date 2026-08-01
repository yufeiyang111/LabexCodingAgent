package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreatePlanToolVerificationTest {

    @TempDir
    Path workspace;

    @Test
    void rejectsCompletingAVerificationTaskBeforeAnySuccessfulVerification() throws Exception {
        CreatePlanTool tool = new CreatePlanTool();
        AgentContext context = new AgentContext("session-1", 7, null, "conversation-1", 71L, workspace,
                new ArrayList<>(), 0);
        JsonObject create = new JsonObject();
        create.addProperty("action", "create");
        JsonArray tasks = new JsonArray();
        JsonObject task = new JsonObject();
        task.addProperty("title", "Verify frontend build");
        task.addProperty("description", "Run npm build verification");
        tasks.add(task);
        create.add("tasks", tasks);
        assertTrue(tool.execute(context, create).isSuccess());
        JsonObject complete = new JsonObject();
        complete.addProperty("action", "complete");
        complete.addProperty("task_index", 1);

        assertFalse(tool.execute(context, complete).isSuccess());

        context.incrementVerificationCount();
        assertFalse(tool.execute(context, complete).isSuccess());

        context.recordTrustedVerification("run_tests");
        assertTrue(tool.execute(context, complete).isSuccess());
    }
}
