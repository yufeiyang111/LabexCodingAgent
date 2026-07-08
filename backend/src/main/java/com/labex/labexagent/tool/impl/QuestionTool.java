package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QuestionTool
implements AgentTool {
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("question")
                .description("Ask one concise clarifying question when the task is blocked and the user must decide before the agent can continue.")
                .stringProperty("question", "The exact question to show to the user.", true)
                .stringProperty("summary", "Short label shown in the UI, such as 'Choose implementation scope'.", false)
                .arrayProperty("options", "Optional answer choices. Leave empty for free-form text answers.", Map.of("type", "string"), false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String question = ToolSupport.stringArg((JsonObject)args, "question", "");
        if (question.isBlank()) {
            return ToolResult.failed("question is required");
        }
        return ToolResult.ok((String)("\u9700\u8981\u7528\u6237\u786e\u8ba4\uff1a" + question));
    }
}
