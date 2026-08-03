package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.CancellationToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderCancellationTest {

    @Test
    void exposesCancellationAwareNonStreamingProviderContract() throws Exception {
        Method method = cancellationAwareMethod();

        assertEquals(Map.class, method.getReturnType());
    }

    @Test
    void cancellingAnActiveRunDisconnectsAHangingNonStreamingRequest() throws Exception {
        CountDownLatch responseStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> holdJsonResponse(
                exchange, responseStarted, releaseResponse));
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AgentCancellationRegistry registry = new AgentCancellationRegistry();
        AgentCancellationRegistry.ActiveRun activeRun = registry.register("session-cancel", 1, 2, 3L);
        try {
            OpenAiCompatibleProvider provider = providerForLocalServer();
            Method method = cancellationAwareMethod();
            LlmProvider.LlmConfig config = new LlmProvider.LlmConfig(
                    "test-key", baseUrl(server), "test-model", 32, 0.1,
                    1_000, 20_000, 2);
            Future<Map<String, Object>> future = executor.submit(() -> invokeChat(
                    method, provider, config, activeRun));
            assertTrue(responseStarted.await(2, TimeUnit.SECONDS), "Provider never reached the hanging response body");

            long cancellationStarted = System.nanoTime();
            registry.cancel("session-cancel", 1, 2);
            Map<String, Object> result = future.get(2, TimeUnit.SECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancellationStarted);

            assertEquals("cancelled", result.get("type"));
            assertTrue(elapsedMillis < 1_500L, "Cancellation took " + elapsedMillis + "ms");
        } finally {
            releaseResponse.countDown();
            registry.complete(activeRun);
            executor.shutdownNow();
            server.stop(0);
        }
    }

    private static Method cancellationAwareMethod() throws NoSuchMethodException {
        return LlmProvider.class.getMethod("chatWithTools", String.class, List.class, List.class,
                LlmProvider.LlmConfig.class, CancellationToken.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeChat(Method method, LlmProvider provider,
                                                   LlmProvider.LlmConfig config,
                                                   CancellationToken token) throws Exception {
        return (Map<String, Object>) method.invoke(provider, "system", List.of(), List.of(), config, token);
    }

    private static void holdJsonResponse(HttpExchange exchange, CountDownLatch responseStarted,
                                         CountDownLatch releaseResponse) {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            responseStarted.countDown();
            releaseResponse.await(10, TimeUnit.SECONDS);
            exchange.getResponseBody().write("{\"choices\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Client cancellation is expected to close the exchange while the response is held.
        } finally {
            exchange.close();
        }
    }

    private static OpenAiCompatibleProvider providerForLocalServer() {
        return new OpenAiCompatibleProvider(new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("8.8.8.8")}));
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}