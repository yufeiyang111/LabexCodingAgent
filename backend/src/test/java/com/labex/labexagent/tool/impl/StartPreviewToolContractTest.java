package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.tool.ToolDefinition;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StartPreviewToolContractTest {
    @Test
    void exposesOnlyCanonicalPreviewArgumentsToTheModel() {
        ToolDefinition definition = new StartPreviewTool(null).definition();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) definition.getInputSchema().get("properties");
        assertThat(properties).containsKeys("command", "port", "workdir", "readiness_path");
        assertThat(properties).doesNotContainKeys("working_directory", "workingDirectory", "cwd");
    }
}
