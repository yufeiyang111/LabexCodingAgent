package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.IncrementalContextService;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RetrieveContextTool
implements AgentTool {
    private final IncrementalContextService incrementalContextService;

    public RetrieveContextTool() {
        this(new IncrementalContextService());
    }

    @Autowired
    public RetrieveContextTool(IncrementalContextService incrementalContextService) {
        this.incrementalContextService = incrementalContextService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("retrieve_context")
                .description("Search the incremental project index for files and code snippets relevant to a task. Results are ranked by indexed relevance; use read_file for complete file contents.")
                .stringProperty("query", "search terms or task description", true)
                .intProperty("limit", "maximum ranked results, default 5 and maximum 20", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) throws Exception {
        String query = args.has("query") ? args.get("query").getAsString() : "";
        if (query.isBlank()) {
            return ToolResult.failed("query is required");
        }
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 5;
        IncrementalContextService.RetrievalResult retrieval = incrementalContextService.retrieve(
                context.getProject(), query, List.of(), Math.max(1, Math.min(limit, 20)));
        if (retrieval.hits().isEmpty()) {
            return ToolResult.ok("未找到与 '" + query + "' 相关的上下文。");
        }
        StringBuilder output = new StringBuilder("上下文检索完成，耗时 ")
                .append(retrieval.elapsedMillis()).append(" ms，复用索引文件 ")
                .append(retrieval.indexStats().reusedFiles()).append(" 个。\n");
        for (IncrementalContextService.RetrievalHit hit : retrieval.hits()) {
            output.append("\n文件：").append(hit.path())
                    .append(" score=").append(String.format(java.util.Locale.ROOT, "%.2f", hit.score()))
                    .append(" reasons=").append(String.join(", ", hit.reasons())).append("\n")
                    .append(hit.preview());
        }
        return ToolResult.ok(output.toString());
    }
}
