package com.labex.labexagent.runtime;

import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前一次 Provider 请求可见工具及其仅限本次运行的执行绑定。 */
public record ToolExposure(List<ToolDefinition> definitions,
                           Map<String, AgentTool> scopedTools,
                           ToolExposureSnapshot snapshot,
                           boolean restoredFromSnapshot) {
    public ToolExposure {
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
        scopedTools = scopedTools == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(scopedTools));
    }
}
