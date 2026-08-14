package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentToolCallIdPolicy;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

/** Reads bounded pages from a durable Tool Part in the active task. */
@Component
public class ReadToolOutputTool implements AgentTool {
    private final AgentRunPartService partService;

    public ReadToolOutputTool(AgentRunPartService partService) {
        this.partService = partService;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("read_tool_output")
                .description("Read a bounded page of the durable raw output from an earlier tool call in the active task. Use this only after a tool result says its model preview was truncated.")
                .stringProperty("tool_call_id", "The earlier tool call id named by the truncated result", true)
                .intProperty("offset", "Zero-based UTF-16 character offset. Use next_offset from the previous page.", false)
                .intProperty("limit", "Maximum characters to return. The server clamps this to a safe page size.", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String toolCallId = ToolSupport.stringArgMulti(args, "", "tool_call_id", "toolCallId");
        if (toolCallId == null || toolCallId.isBlank()) {
            return ToolResult.failed("tool_call_id is required");
        }
        if (!AgentToolCallIdPolicy.isValid(toolCallId)) {
            return ToolResult.failed("tool_call_id is invalid");
        }
        if (context == null || context.getProject() == null) {
            return ToolResult.failed("Tool output is unavailable for the active task");
        }
        try {
            AgentRunPartService.ToolOutputSlice slice = partService.readOwnedToolOutput(
                    context.getStudentId(), context.getProject().getProjectId(), context.getTaskId(), toolCallId,
                    ToolSupport.intArg(args, "offset", 0), ToolSupport.intArg(args, "limit", 0));
            StringBuilder output = new StringBuilder();
            output.append("[read_tool_output tool_call_id=").append(slice.toolCallId())
                    .append(" tool=").append(slice.toolName() == null ? "" : slice.toolName())
                    .append(" chars=").append(slice.offset()).append("-").append(slice.nextOffset())
                    .append("/").append(slice.totalChars())
                    .append(" next_offset=").append(slice.nextOffset()).append("]\n");
            output.append(slice.content());
            if (slice.hasMore()) {
                output.append("\n\n[Output page is partial. Use offset=").append(slice.nextOffset())
                        .append(" with read_tool_output to continue.]");
            } else {
                output.append("\n\n[End of stored tool output.]");
            }
            return ToolResult.ok(output.toString());
        } catch (IllegalArgumentException exception) {
            return ToolResult.failed("Tool output is unavailable for the active task: " + exception.getMessage());
        }
    }
}
