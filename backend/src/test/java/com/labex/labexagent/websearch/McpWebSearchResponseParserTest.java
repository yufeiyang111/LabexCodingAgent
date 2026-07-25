package com.labex.labexagent.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class McpWebSearchResponseParserTest {
    private final McpWebSearchResponseParser parser = new McpWebSearchResponseParser();

    @Test
    void extractsTextFromJsonRpcResponse() throws Exception {
        String content = parser.extractContent("""
                {"jsonrpc":"2.0","id":1,"result":{"content":[
                  {"type":"text","text":"first result"},
                  {"type":"text","text":"second result"}
                ]}}
                """);

        assertEquals("first result\n\nsecond result", content);
    }

    @Test
    void extractsTextFromServerSentEventResponse() throws Exception {
        String content = parser.extractContent("""
                event: message
                data: {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"SSE result"}]}}

                """);

        assertEquals("SSE result", content);
    }

    @Test
    void rejectsJsonRpcErrorsWithoutExposingProviderDetails() {
        WebSearchException error = assertThrows(WebSearchException.class,
                () -> parser.extractContent("{\"jsonrpc\":\"2.0\",\"error\":{\"message\":\"secret detail\"}}"));

        assertEquals("Web-search provider returned an error", error.getMessage());
    }
}
