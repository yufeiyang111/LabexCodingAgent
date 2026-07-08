package com.labex.labexagent.tool;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;

public interface AgentTool {
    public ToolDefinition definition();

    public ToolResult execute(AgentContext var1, JsonObject var2) throws Exception;
}

