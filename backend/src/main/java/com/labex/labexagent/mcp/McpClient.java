package com.labex.labexagent.mcp;

import com.google.gson.*;
import com.labex.labexagent.execution.ProcessExecutionRequest;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * MCP (Model Context Protocol) 客户端
 * 支持三种传输协议：
 * - stdio: 本地进程通信
 * - HTTP: HTTP JSON-RPC
 * - SSE: Server-Sent Events
 */
@Slf4j
public class McpClient implements Closeable {

    private static final Gson gson = new GsonBuilder().create();

    private final String serverKey;
    private final String transport; // "stdio", "http", "sse"
    private final String endpoint;
    private final Supplier<String> authHeaderSupplier;
    private final Map<String, String> env;
    private final SandboxWorker sandboxWorker;
    private final WorkerRunSpec workerRun;
    private final OutboundUrlPolicy outboundUrlPolicy;

    private SandboxWorker.WorkerProcess stdioProcess;
    private OutputStream stdioIn;
    private BufferedReader stdioOut;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private long requestIdCounter = 0;
    private volatile boolean connected = false;
    private volatile boolean closed = false;

    // 工具列表缓存
    private List<ToolDef> tools = new ArrayList<>();

    /**
     * MCP 工具定义
     */
    public static class ToolDef {
        private final String name;
        private final String description;
        private final JsonObject inputSchema;

        public ToolDef(String name, String description, JsonObject inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public JsonObject getInputSchema() { return inputSchema; }
    }

    /**
     * MCP 工具调用结果
     */
    public static class CallResult {
        private final boolean success;
        private final String content;
        private final String error;

        public CallResult(boolean success, String content, String error) {
            this.success = success;
            this.content = content;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public String getContent() { return content; }
        public String getError() { return error; }

        public static CallResult ok(String content) {
            return new CallResult(true, content, null);
        }

        public static CallResult fail(String error) {
            return new CallResult(false, null, error);
        }
    }

    public McpClient(String serverKey, String transport, String endpoint, String authHeader, Map<String, String> env) {
        this(serverKey, transport, endpoint, () -> authHeader, env, null, null, new OutboundUrlPolicy());
    }

    public McpClient(
            String serverKey,
            String transport,
            String endpoint,
            String authHeader,
            Map<String, String> env,
            SandboxWorker sandboxWorker,
            WorkerRunSpec workerRun) {
        this(serverKey, transport, endpoint, () -> authHeader, env, sandboxWorker, workerRun, new OutboundUrlPolicy());
    }

    public McpClient(
            String serverKey,
            String transport,
            String endpoint,
            String authHeader,
            Map<String, String> env,
            SandboxWorker sandboxWorker,
            WorkerRunSpec workerRun,
            OutboundUrlPolicy outboundUrlPolicy) {
        this(serverKey, transport, endpoint, () -> authHeader, env, sandboxWorker, workerRun, outboundUrlPolicy);
    }

    public static McpClient withAuthHeaderSupplier(
            String serverKey,
            String transport,
            String endpoint,
            Supplier<String> authHeaderSupplier,
            Map<String, String> env,
            SandboxWorker sandboxWorker,
            WorkerRunSpec workerRun,
            OutboundUrlPolicy outboundUrlPolicy) {
        return new McpClient(
                serverKey, transport, endpoint, authHeaderSupplier, env, sandboxWorker, workerRun, outboundUrlPolicy);
    }

    private McpClient(
            String serverKey,
            String transport,
            String endpoint,
            Supplier<String> authHeaderSupplier,
            Map<String, String> env,
            SandboxWorker sandboxWorker,
            WorkerRunSpec workerRun,
            OutboundUrlPolicy outboundUrlPolicy) {
        this.serverKey = serverKey;
        this.transport = transport;
        this.endpoint = endpoint;
        this.authHeaderSupplier = authHeaderSupplier == null ? () -> "" : authHeaderSupplier;
        this.env = env != null ? env : new HashMap<>();
        this.sandboxWorker = sandboxWorker;
        this.workerRun = workerRun;
        this.outboundUrlPolicy = Objects.requireNonNull(outboundUrlPolicy, "outboundUrlPolicy");
    }

    /**
     * 连接到 MCP 服务器
     */
    public synchronized void connect() throws IOException {
        if (connected) return;

        switch (transport) {
            case "stdio" -> connectStdio();
            case "http" -> connectHttp();
            case "sse" -> connectSse();
            default -> throw new IOException("Unknown transport: " + transport);
        }

        connected = true;
        log.info("MCP client '{}' connected via {}", serverKey, transport);

        // 初始化：获取工具列表
        try {
            initialize();
            refreshTools();
        } catch (Exception e) {
            log.warn("MCP initialization failed for '{}': {}", serverKey, e.getMessage());
        }
    }

    /**
     * stdio 传输连接
     */
    private void connectStdio() throws IOException {
        stdioProcess = startStdioProcess();
        stdioIn = stdioProcess.standardInput();
        stdioOut = new BufferedReader(new InputStreamReader(stdioProcess.standardOutput(), StandardCharsets.UTF_8));

        // 启动读取线程
        executor.submit(this::readStdioMessages);
    }

    SandboxWorker.WorkerProcess startStdioProcess() throws IOException {
        if (sandboxWorker == null || workerRun == null) {
            throw new IOException("stdio MCP requires a sandbox worker workspace");
        }
        String[] cmdParts = endpoint == null ? new String[0] : endpoint.trim().split("\\s+");
        if (cmdParts.length == 0 || cmdParts[0].isBlank()) {
            throw new IOException("stdio MCP command is required");
        }
        return sandboxWorker.startProcess(workerRun, new ProcessExecutionRequest(
                List.of(cmdParts), workerRun.workspaceRoot(), Duration.ofHours(4), 1));
    }

    /**
     * HTTP 传输连接（验证端点可达）
     */
    private void connectHttp() throws IOException {
        // HTTP 是无连接的，只需验证端点可达
        URI endpointUri = validatedHttpEndpoint();
        HttpURLConnection conn = null;
        try {
            URL url = endpointUri.toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("OPTIONS");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(false);
            String authHeader = authorizationHeader();
            if (!authHeader.isBlank()) {
                conn.setRequestProperty("Authorization", authHeader);
            }
            int code = conn.getResponseCode();
            if (isRedirect(code)) {
                validateRedirect(endpointUri, conn.getHeaderField("Location"));
                throw new IOException("MCP HTTP redirects are not supported");
            }
            // 任何响应都表示服务器可达
        } catch (Exception e) {
            throw new IOException("MCP HTTP endpoint unreachable: " + endpoint, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * SSE 传输连接
     */
    private void connectSse() throws IOException {
        // SSE 连接通过 HTTP GET 建立长连接
        // 简化实现：使用 HTTP 轮询
        connectHttp();
    }

    /**
     * 初始化 MCP 会话
     */
    private void initialize() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "LabexAgent");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);

        JsonObject result = sendRequest("initialize", params);
        log.debug("MCP '{}' initialized: {}", serverKey, result);
    }

    /**
     * 刷新工具列表
     */
    public void refreshTools() throws Exception {
        JsonObject result = sendRequest("tools/list", new JsonObject());
        tools.clear();

        JsonArray toolsArray = result.has("tools") ? result.getAsJsonArray("tools") : new JsonArray();
        for (JsonElement elem : toolsArray) {
            JsonObject tool = elem.getAsJsonObject();
            String name = tool.get("name").getAsString();
            String desc = tool.has("description") ? tool.get("description").getAsString() : "";
            JsonObject schema = tool.has("inputSchema") ? tool.getAsJsonObject("inputSchema") : new JsonObject();
            tools.add(new ToolDef(name, desc, schema));
        }

        log.info("MCP '{}': {} tools discovered", serverKey, tools.size());
    }

    /**
     * 获取工具列表
     */
    public List<ToolDef> getTools() {
        return Collections.unmodifiableList(tools);
    }

    /**
     * 调用工具
     */
    public CallResult callTool(String toolName, String argumentsJson) {
        if (!connected) {
            return CallResult.fail("MCP client not connected: " + serverKey);
        }

        try {
            JsonObject params = new JsonObject();
            params.addProperty("name", toolName);
            JsonObject arguments = argumentsJson != null && !argumentsJson.isBlank()
                ? JsonParser.parseString(argumentsJson).getAsJsonObject()
                : new JsonObject();
            params.add("arguments", arguments);

            JsonObject result = sendRequest("tools/call", params);

            // 解析结果
            JsonArray content = result.has("content") ? result.getAsJsonArray("content") : new JsonArray();
            StringBuilder sb = new StringBuilder();
            boolean isError = result.has("isError") && result.get("isError").getAsBoolean();

            for (JsonElement elem : content) {
                JsonObject item = elem.getAsJsonObject();
                String type = item.has("type") ? item.get("type").getAsString() : "text";
                if ("text".equals(type)) {
                    sb.append(item.get("text").getAsString());
                } else if ("image".equals(type)) {
                    sb.append("[Image: ").append(item.has("mimeType") ? item.get("mimeType").getAsString() : "unknown").append("]");
                }
            }

            return isError ? CallResult.fail(sb.toString()) : CallResult.ok(sb.toString());
        } catch (Exception e) {
            log.error("MCP tool call failed: {}.{}", serverKey, toolName, e);
            return CallResult.fail("Tool call failed: " + e.getMessage());
        }
    }

    /**
     * 发送 JSON-RPC 请求
     */
    private JsonObject sendRequest(String method, JsonObject params) throws Exception {
        long id = ++requestIdCounter;

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);

        String json = gson.toJson(request);

        switch (transport) {
            case "stdio" -> {
                return sendStdioRequest(id, json);
            }
            case "http", "sse" -> {
                return sendHttpRequest(json);
            }
            default -> throw new IOException("Unknown transport: " + transport);
        }
    }

    /**
     * 通过 stdio 发送请求
     */
    private JsonObject sendStdioRequest(long id, String json) throws Exception {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        // 发送请求
        String message = "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json;
        synchronized (stdioIn) {
            stdioIn.write(message.getBytes(StandardCharsets.UTF_8));
            stdioIn.flush();
        }

        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new IOException("MCP request timed out: " + json.substring(0, Math.min(100, json.length())));
        }
    }

    /**
     * 通过 HTTP 发送请求
     */
    private JsonObject sendHttpRequest(String json) throws IOException {
        URI endpointUri = validatedHttpEndpoint();
        URL url = endpointUri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        String authHeader = authorizationHeader();
        if (!authHeader.isBlank()) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (isRedirect(code)) {
                validateRedirect(endpointUri, conn.getHeaderField("Location"));
                throw new IOException("MCP HTTP redirects are not supported");
            }
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                throw new IOException("MCP HTTP response has no body");
            }

            String response;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                response = sb.toString();
            }

            JsonObject resp = JsonParser.parseString(response).getAsJsonObject();
            if (resp.has("error")) {
                JsonObject error = resp.getAsJsonObject("error");
                throw new IOException("MCP error: " + error);
            }
            return resp.has("result") ? resp.getAsJsonObject("result") : new JsonObject();
        } finally {
            conn.disconnect();
        }
    }

    private URI validatedHttpEndpoint() throws IOException {
        try {
            return outboundUrlPolicy.validate(endpoint).uri();
        } catch (OutboundUrlPolicy.RejectedOutboundUrlException e) {
            throw new IOException("MCP HTTP endpoint is blocked", e);
        }
    }

    private void validateRedirect(URI current, String location) throws IOException {
        try {
            outboundUrlPolicy.validateRedirect(current, location);
        } catch (OutboundUrlPolicy.RejectedOutboundUrlException e) {
            throw new IOException("MCP HTTP redirect is blocked", e);
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }

    private String authorizationHeader() {
        String authHeader = authHeaderSupplier.get();
        return authHeader == null ? "" : authHeader;
    }

    /**
     * 读取 stdio 消息
     */
    private void readStdioMessages() {
        try {
            while (!closed && stdioOut != null) {
                // 读取 Content-Length 头
                String headerLine = stdioOut.readLine();
                if (headerLine == null) break;

                int contentLength = -1;
                if (headerLine.startsWith("Content-Length:")) {
                    contentLength = Integer.parseInt(headerLine.substring("Content-Length:".length()).trim());
                }

                // 跳过空行
                while (headerLine != null && !headerLine.isBlank()) {
                    headerLine = stdioOut.readLine();
                }

                if (contentLength <= 0) continue;

                // 读取消息体
                char[] buffer = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = stdioOut.read(buffer, read, contentLength - read);
                    if (n == -1) break;
                    read += n;
                }

                String messageBody = new String(buffer, 0, read);
                handleMessage(messageBody);
            }
        } catch (Exception e) {
            if (!closed) {
                log.error("MCP stdio read error for '{}': {}", serverKey, e.getMessage());
            }
        }
    }

    /**
     * 处理收到的消息
     */
    private void handleMessage(String json) {
        try {
            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();

            if (msg.has("id") && !msg.get("id").isJsonNull()) {
                // 这是对请求的响应
                long id = msg.get("id").getAsLong();
                CompletableFuture<JsonObject> future = pendingRequests.remove(id);
                if (future != null) {
                    if (msg.has("error")) {
                        future.completeExceptionally(
                            new IOException("MCP error: " + msg.getAsJsonObject("error"))
                        );
                    } else {
                        future.complete(msg.has("result") ? msg.getAsJsonObject("result") : new JsonObject());
                    }
                }
            } else if (msg.has("method")) {
                // 这是服务器发来的通知
                String method = msg.get("method").getAsString();
                log.debug("MCP notification from '{}': {}", serverKey, method);

                // 处理工具列表变更通知
                if ("notifications/tools/list_changed".equals(method)) {
                    try {
                        refreshTools();
                    } catch (Exception e) {
                        log.warn("Failed to refresh tools after notification: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse MCP message: {}", e.getMessage());
        }
    }

    /**
     * 断开连接
     */
    public synchronized void disconnect() {
        if (!connected) return;
        closed = true;
        connected = false;

        if (stdioProcess != null) {
            stdioProcess.terminate();
            stdioProcess = null;
        }

        pendingRequests.values().forEach(f -> f.completeExceptionally(new IOException("Disconnected")));
        pendingRequests.clear();

        log.info("MCP client '{}' disconnected", serverKey);
    }

    @Override
    public void close() {
        disconnect();
        executor.shutdownNow();
    }

    public boolean isConnected() {
        return connected;
    }

    public String getServerKey() {
        return serverKey;
    }

    public String getTransport() {
        return transport;
    }
}
