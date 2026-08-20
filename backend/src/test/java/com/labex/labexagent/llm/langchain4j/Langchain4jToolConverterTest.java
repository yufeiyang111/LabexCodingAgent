package com.labex.labexagent.llm.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Langchain4jToolConverterTest {

    @Test
    void convertsToolMapsToToolSpecifications() {
        List<Map<String, Object>> tools = List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "read_file",
                                "description", "Read a file from disk",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "path", Map.of("type", "string", "description", "Path to file"),
                                                "line_count", Map.of("type", "integer", "description", "Number of lines")
                                        ),
                                        "required", List.of("path")
                                )
                        )
                ),
                Map.of(
                        "name", "run_command",
                        "description", "Execute a terminal command",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "command", Map.of("type", "string")
                                ),
                                "required", List.of("command")
                        )
                )
        );

        List<ToolSpecification> specs = Langchain4jToolConverter.convert(tools);

        assertEquals(2, specs.size());

        ToolSpecification spec1 = specs.get(0);
        assertEquals("read_file", spec1.name());
        assertEquals("Read a file from disk", spec1.description());
        assertNotNull(spec1.parameters());

        ToolSpecification spec2 = specs.get(1);
        assertEquals("run_command", spec2.name());
        assertEquals("Execute a terminal command", spec2.description());
        assertNotNull(spec2.parameters());
    }

    @Test
    void handlesEmptyOrNullToolsGracefully() {
        assertTrue(Langchain4jToolConverter.convert(null).isEmpty());
        assertTrue(Langchain4jToolConverter.convert(List.of()).isEmpty());
    }
}
