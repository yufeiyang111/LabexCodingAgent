package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolCallExtractorTest {
    @Test
    void acceptsOneExplicitJsonEnvelopeWithNestedAndEscapedArguments() {
        ToolCallExtractor.Extraction result = ToolCallExtractor.extract("""
                <tool_call>{"name":"read_file","arguments":{"file_path":"docs/{sample}.md","note":"say \\\"hi\\\""}}</tool_call>
                """);

        assertEquals(ToolCallExtractor.Status.VALID, result.status());
        assertEquals("read_file", result.toolName());
        assertEquals("docs/{sample}.md", result.arguments().get("file_path").getAsString());
        assertEquals("say \"hi\"", result.arguments().get("note").getAsString());
    }

    @Test
    void acceptsOneExplicitInvokeEnvelope() {
        ToolCallExtractor.Extraction result = ToolCallExtractor.extract("""
                <invoke name="read_file"><parameter name="file_path">README.md</parameter></invoke>
                """);

        assertEquals(ToolCallExtractor.Status.VALID, result.status());
        assertEquals("read_file", result.toolName());
        assertEquals("README.md", result.arguments().get("file_path").getAsString());
        assertEquals("invoke", result.format());
    }

    @Test
    void acceptsOneExplicitToolCallFence() {
        ToolCallExtractor.Extraction result = ToolCallExtractor.extract("""
                ```tool_call
                {"name":"list_files","arguments":{"path":""}}
                ```
                """);

        assertEquals(ToolCallExtractor.Status.VALID, result.status());
        assertEquals("list_files", result.toolName());
    }

    @Test
    void ordinaryProseAndCodeExamplesNeverBecomeToolCalls() {
        for (String content : new String[]{
                "例如可以调用 read_file(README.md) 查看内容，但这里仅是在解释。",
                "调用工具 read_file，参数是 README.md，这句话不是协议。",
                "示例 JSON：{\"tool\":\"shell\",\"arguments\":{\"command\":\"whoami\"}}",
                "```java\nread_file(\"README.md\");\n```"
        }) {
            ToolCallExtractor.Extraction result = ToolCallExtractor.extract(content);
            assertEquals(ToolCallExtractor.Status.NONE, result.status(), content);
            assertFalse(result.executable(), content);
        }
    }

    @Test
    void explicitEnvelopeEmbeddedInExplanatoryProseIsInvalid() {
        ToolCallExtractor.Extraction result = ToolCallExtractor.extract("""
                下面只是协议示例，不要执行：
                <tool_call>{"name":"shell","arguments":{"command":"whoami"}}</tool_call>
                """);

        assertEquals(ToolCallExtractor.Status.INVALID, result.status());
        assertFalse(result.executable());
        assertTrue(result.reason().contains("entire model output"));
    }
    @Test
    void truncatedExplicitEnvelopeIsIncomplete() {
        ToolCallExtractor.Extraction result = ToolCallExtractor.extract(
                "<tool_call>{\"name\":\"read_file\",\"arguments\":{");

        assertEquals(ToolCallExtractor.Status.INCOMPLETE, result.status());
        assertFalse(result.executable());
    }

    @Test
    void multipleExplicitCandidatesAreAmbiguous() {
        ToolCallExtractor.Extraction result = ToolCallExtractor.extract("""
                <tool_call>{"name":"read_file","arguments":{"file_path":"a"}}</tool_call>
                <tool_call>{"name":"read_file","arguments":{"file_path":"b"}}</tool_call>
                """);

        assertEquals(ToolCallExtractor.Status.AMBIGUOUS, result.status());
        assertFalse(result.executable());
    }

    @Test
    void malformedOrNonObjectPayloadIsInvalid() {
        ToolCallExtractor.Extraction malformed = ToolCallExtractor.extract(
                "<tool_call>{\"name\":\"read_file\",}</tool_call>");
        ToolCallExtractor.Extraction nonObject = ToolCallExtractor.extract(
                "<tool_call>[\"read_file\",{}]</tool_call>");

        assertEquals(ToolCallExtractor.Status.INVALID, malformed.status());
        assertEquals(ToolCallExtractor.Status.INVALID, nonObject.status());
        assertTrue(malformed.reason().contains("JSON"));
    }

    @Test
    void incompleteParameterEnvelopeCannotFallBackToPartialArguments() {
        ToolCallExtractor.Extraction result = ToolCallExtractor.extract("""
                <invoke name="write_file"><parameter name="file_path">a.txt</parameter><parameter name="content">partial</invoke>
                """);

        assertEquals(ToolCallExtractor.Status.INCOMPLETE, result.status());
        assertFalse(result.executable());
    }
}
