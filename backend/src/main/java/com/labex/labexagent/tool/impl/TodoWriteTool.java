package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

@Component
public class TodoWriteTool
implements AgentTool {
    public ToolDefinition definition() {
        return ToolDefinition.builder().name("todo_write").description("\u8bb0\u5f55\u6216\u66f4\u65b0\u5f53\u524d\u4efb\u52a1\u7684\u77ed todo \u5217\u8868\u3002\u53ea\u653e\u5173\u952e\u6b65\u9aa4\uff0c\u907f\u514d\u957f\u8ba1\u5212\u5360\u7528\u4e0a\u4e0b\u6587\u3002").stringProperty("todos", "Markdown or plain text todo list", true).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String todos = ToolSupport.stringArg((JsonObject)args, "todos", "");
        if (todos.isBlank()) {
            return ToolResult.failed("todos is required");
        }
        return ToolResult.ok((String)("Current todo list:\n" + ToolSupport.limit((String)todos, 2000)));
    }
}

