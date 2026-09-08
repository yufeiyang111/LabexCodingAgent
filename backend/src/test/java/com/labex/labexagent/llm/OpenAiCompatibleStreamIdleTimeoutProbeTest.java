package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.network.OutboundUrlPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 复现生产「mid_stream 卡 30s 即超时」疑点的判定实验：
 * 服务端先吐 2 个 SSE 块，再停滞 40 秒，最后吐 done。
 * 若首块后的 read timeout 已切到 300s，则 chatStream 应在约 40s 后正常完成；
 * 若 30s 空闲超时仍在生效，则约 32s 抛 SocketTimeoutException —— 测试失败并给出结论。
 */
class OpenAiCompatibleStreamIdleTimeoutProbeTest {

    @Test
    void midStreamIdleGapShorterThanReadTimeoutMustNotAbortTheStream() throws Exception {
        AtomicLong firstEventAt = new AtomicLong();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write(sse("data: {\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n"));
            os.flush();
            firstEventAt.set(System.nanoTime());
            os.write(sse("data: {\"choices\":[{\"delta\":{\"content\":\"world\"}}]}\n\n"));
            os.flush();
            try {
                Thread.sleep(40_000);
            } catch (InterruptedException ignored) {
            }
            os.write(sse("data: {\"choices\":[{\"delta\":{\"content\":\" tail\"},\"finish_reason\":\"stop\"}]}\n\n"));
            os.flush();
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        });
        server.start();
        try {
            LlmProvider.LlmConfig config = new LlmProvider.LlmConfig(
                    "test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "probe-model",
                    64, 0.1, 1_000, 300_000, 0, false, null, "high");
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(new OutboundUrlPolicy(host ->
                    new InetAddress[] {InetAddress.getByName("8.8.8.8")}));
            long startedAt = System.nanoTime();
            provider.chatStream("system", List.of(), List.of(), config, chunks::add);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            String joined = chunks.stream().map(c -> c.content() == null ? "" : c.content()).reduce("", String::concat);
            assertTrue(joined.contains("tail"),
                    () -> "流在 40s 空闲期被提前中断（mid_stream 空闲超时仍为 30s），elapsed=" + elapsedMs
                            + "ms, chunks=" + chunks.size() + " —— 当前实现存在 30 秒流空闲超时 bug");
            assertTrue(elapsedMs >= 39_000, () -> "过早返回 elapsed=" + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
    }

    private byte[] sse(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}
