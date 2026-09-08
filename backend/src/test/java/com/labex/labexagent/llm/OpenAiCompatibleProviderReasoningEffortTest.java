package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.network.OutboundUrlPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderReasoningEffortTest {

    @Test
    void sendsConfiguredReasoningEffort() throws Exception {
        HttpServer server = startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"reasoning_effort\":\"high\""));
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            provider().chatStream("system", List.of(), List.of(), reasoningConfig(server), chunks::add);
            assertTrue(chunks.stream().anyMatch(chunk -> "text_delta".equals(chunk.type())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesNonStreamingRequestWithoutReasoningEffortWhenCompatibleGatewayRejectsIt() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (requestCount.incrementAndGet() == 1) {
                assertTrue(body.contains("\"reasoning_effort\":\"high\""));
                sendJson(exchange, 400, "{\"error\":{\"message\":\"unknown field reasoning_effort\"}}");
                return;
            }
            assertFalse(body.contains("\"reasoning_effort\""));
            sendJson(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });
        try {
            var response = provider().chatWithTools("system", List.of(), List.of(), reasoningConfig(server));
            assertEquals(2, requestCount.get());
            assertEquals("text", response.get("type"));
            assertEquals("ok", response.get("content"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesWithoutReasoningEffortWhenCompatibleGatewayRejectsIt() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            if (requestCount.incrementAndGet() == 1) {
                sendJson(exchange, 400, "{\"error\":{\"message\":\"unknown field reasoning_effort\"}}");
                return;
            }
            assertFalse(body.contains("\"reasoning_effort\""));
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            provider().chatStream("system", List.of(), List.of(), reasoningConfig(server), chunks::add);
            assertEquals(2, requestCount.get());
            assertTrue(bodies.get(0).contains("\"reasoning_effort\":\"high\""));
            assertFalse(bodies.get(1).contains("\"reasoning_effort\""));
            assertTrue(chunks.stream().anyMatch(chunk -> "text_delta".equals(chunk.type())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesWithoutReasoningEffortWhenGatewayDemandsAdaptiveThinking() throws Exception {
        // 复现生产案例：new-api 网关把 reasoning_effort 转译成 Anthropic 原生 thinking 后
        // 被 claude-sonnet-5 adaptive-only 通道拒绝，文案不含 "reasoning_effort" 字样。
        AtomicInteger requestCount = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            if (requestCount.incrementAndGet() == 1) {
                sendJson(exchange, 400, "{\"error\":{\"message\":\"model \\\"claude-sonnet-5\\\" requires adaptive thinking and does not support native budget_tokens\",\"type\":\"new_api_error\",\"param\":\"\",\"code\":null}}");
                return;
            }
            assertFalse(body.contains("\"reasoning_effort\""));
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            provider().chatStream("system", List.of(), List.of(), reasoningConfig(server), chunks::add);
            assertEquals(2, requestCount.get());
            assertTrue(bodies.get(0).contains("\"reasoning_effort\":\"high\""));
            assertFalse(bodies.get(1).contains("\"reasoning_effort\""));
            assertTrue(chunks.stream().anyMatch(chunk -> "text_delta".equals(chunk.type())));
        } finally {
            server.stop(0);
        }
    }

    private static OpenAiCompatibleProvider provider() {
        return new OpenAiCompatibleProvider(new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("8.8.8.8")}));
    }

    private static LlmProvider.LlmConfig reasoningConfig(HttpServer server) {
        return new LlmProvider.LlmConfig(
                "test-key", "http://127.0.0.1:" + server.getAddress().getPort(), "test-model", 64, 0.1,
                1_000, 5_000, 0, false, null, "high");
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chat/completions", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static void sendSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
