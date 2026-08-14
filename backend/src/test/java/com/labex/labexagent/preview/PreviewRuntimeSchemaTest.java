package com.labex.labexagent.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewRuntimeSchemaTest {

    @Test
    void schemaStoresASeparateDurablePreviewRunWithoutCommandPlaintext() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/sql/schema.sql"));
        Matcher table = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS t_agent_preview_run .*?;").matcher(schema);

        assertTrue(table.find(), "schema.sql must define t_agent_preview_run");
        String block = table.group();
        assertTrue(block.contains("preview_id VARCHAR(64) NOT NULL PRIMARY KEY"));
        assertTrue(block.contains("student_id INT NOT NULL"));
        assertTrue(block.contains("project_id INT NOT NULL"));
        assertTrue(block.contains("task_id BIGINT DEFAULT NULL"));
        assertTrue(block.contains("status VARCHAR(32) NOT NULL"));
        assertTrue(block.contains("process_id BIGINT DEFAULT NULL"));
        assertTrue(block.contains("output_path VARCHAR(1024) DEFAULT NULL"));
        assertTrue(!block.contains("command TEXT"));
        assertTrue(!block.contains("command LONGTEXT"));
        assertTrue(!block.contains("command VARCHAR"));
    }
}
