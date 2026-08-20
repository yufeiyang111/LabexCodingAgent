package com.labex.labexagent.llm.langchain4j;

import static org.junit.jupiter.api.Assertions.*;

import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.llm.ProviderCapabilities;
import com.labex.labexagent.llm.ProviderEventType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Langchain4jChatProviderContractTest {

    @Test
    void advertisesLangchain4jProviderCapabilities() {
        Langchain4jChatProvider provider = new Langchain4jChatProvider();
        assertEquals("langchain4j", provider.getProviderId());
        assertTrue(provider.supportsStreaming());
        assertTrue(provider.supportsToolCalling());

        ProviderCapabilities capabilities = provider.capabilities();
        assertTrue(capabilities.streaming());
        assertTrue(capabilities.toolCalling());
    }

    @Test
    void receivesStreamingTextChunksAndEmitsDone() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"world!\"}}]}\n\n" +
                    "data: [DONE]\n\n");
        });

        try {
            Langchain4jChatProvider provider = new Langchain4jChatProvider();
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            LlmProvider.LlmConfig config = config(server);

            provider.chatStream("system prompt", List.of(), List.of(), config, chunks::add);

            String combinedText = chunks.stream()
                    .filter(c -> c.eventType() == ProviderEventType.TEXT_DELTA)
                    .map(LlmProvider.StreamChunk::content)
                    .reduce("", String::concat);

            assertEquals("Hello world!", combinedText);

            long doneCount = chunks.stream()
                    .filter(c -> c.eventType() == ProviderEventType.DONE)
                    .count();
            assertEquals(1L, doneCount);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesStreamingToolExecutionRequests() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_read_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"main.py\\\"}\"}}]}}]}\n\n" +
                    "data: [DONE]\n\n");
        });

        try {
            Langchain4jChatProvider provider = new Langchain4jChatProvider();
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            LlmProvider.LlmConfig config = config(server);

            List<Map<String, Object>> tools = List.of(
                    Map.of("type", "function", "function", Map.of("name", "read_file", "description", "read"))
            );

            provider.chatStream("system", List.of(), tools, config, chunks::add);

            List<LlmProvider.StreamChunk> toolCalls = chunks.stream()
                    .filter(c -> c.eventType() == ProviderEventType.TOOL_CALL)
                    .toList();

            assertEquals(1, toolCalls.size());
            assertEquals("call_read_1", toolCalls.get(0).toolCallId());
            assertEquals("read_file", toolCalls.get(0).toolName());
            assertEquals("{\"path\":\"main.py\"}", toolCalls.get(0).toolArgs());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void executesSyncChatWithToolsSuccessfully() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Sync response here\"}}]}");
        });

        try {
            Langchain4jChatProvider provider = new Langchain4jChatProvider();
            LlmProvider.LlmConfig config = config(server);

            Map<String, Object> response = provider.chatWithTools("system", List.of(), List.of(), config);

            assertEquals("text", response.get("type"));
            assertEquals("Sync response here", response.get("content"));
        } finally {
            server.stop(0);
        }
    }

    private static LlmProvider.LlmConfig config(HttpServer server) {
        return new LlmProvider.LlmConfig(
                "test-api-key", baseUrl(server), "gpt-4o-mini", 64, 0.1, 2_000, 5_000, 0);
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static void sendSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
