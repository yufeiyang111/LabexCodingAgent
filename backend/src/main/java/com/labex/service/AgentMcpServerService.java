package com.labex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentMcpServer;
import com.labex.mapper.AgentMcpServerMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentMcpServerService extends ServiceImpl<AgentMcpServerMapper, AgentMcpServer> {
    private static final Gson GSON = new Gson();
    private static final int MAX_TOOLS_JSON = 30000;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public List<AgentMcpServer> listByStudent(Integer studentId) {
        return this.list(new LambdaQueryWrapper<AgentMcpServer>()
                .eq(AgentMcpServer::getStudentId, studentId)
                .eq(AgentMcpServer::getStatus, 1)
                .orderByDesc(AgentMcpServer::getIsEnabled)
                .orderByDesc(AgentMcpServer::getUpdateTime));
    }

    public List<AgentMcpServer> listEnabled(Integer studentId) {
        return this.list(new LambdaQueryWrapper<AgentMcpServer>()
                .eq(AgentMcpServer::getStudentId, studentId)
                .eq(AgentMcpServer::getStatus, 1)
                .eq(AgentMcpServer::getIsEnabled, 1)
                .orderByDesc(AgentMcpServer::getUpdateTime)
                .last("LIMIT 10"));
    }

    public AgentMcpServer getOwned(Integer studentId, Integer serverId) {
        return this.lambdaQuery()
                .eq(AgentMcpServer::getStudentId, studentId)
                .eq(AgentMcpServer::getServerId, serverId)
                .eq(AgentMcpServer::getStatus, 1)
                .one();
    }

    public AgentMcpServer findEnabled(Integer studentId, String serverKey) {
        String key = normalizeKey(serverKey);
        if (key.isBlank()) {
            return null;
        }
        return this.lambdaQuery()
                .eq(AgentMcpServer::getStudentId, studentId)
                .eq(AgentMcpServer::getStatus, 1)
                .eq(AgentMcpServer::getIsEnabled, 1)
                .eq(AgentMcpServer::getServerKey, key)
                .last("LIMIT 1")
                .one();
    }

    public AgentMcpServer create(Integer studentId, Map<String, Object> body) {
        AgentMcpServer server = new AgentMcpServer();
        applyBody(server, studentId, body, true);
        this.save(server);
        return server;
    }

    public AgentMcpServer update(Integer studentId, Integer serverId, Map<String, Object> body) {
        AgentMcpServer server = getOwned(studentId, serverId);
        if (server == null) {
            throw new IllegalArgumentException("MCP server not found");
        }
        applyBody(server, studentId, body, false);
        this.updateById(server);
        return server;
    }

    public void delete(Integer studentId, Integer serverId) {
        AgentMcpServer server = getOwned(studentId, serverId);
        if (server == null) {
            throw new IllegalArgumentException("MCP server not found");
        }
        server.setStatus(0);
        server.setUpdateTime(LocalDateTime.now());
        this.updateById(server);
    }

    public String buildPromptContext(Integer studentId) {
        List<AgentMcpServer> servers = listEnabled(studentId);
        if (servers.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("<user_mcp_servers isolation=\"student_only\">\n");
        builder.append("Call external tools through mcp_call only when the task clearly needs them. Never expose secrets in responses.\n");
        for (AgentMcpServer server : servers) {
            builder.append("\n<server key=\"").append(escape(server.getServerKey())).append("\" name=\"")
                    .append(escape(server.getServerName())).append("\" transport=\"")
                    .append(escape(server.getTransport())).append("\">\n");
            if (server.getToolsJson() != null && !server.getToolsJson().isBlank()) {
                builder.append(limit(server.getToolsJson(), 5000)).append("\n");
            } else {
                builder.append("No manifest provided. Use mcp_call with a known tool name only if the user supplied one.\n");
            }
            builder.append("</server>\n");
        }
        builder.append("</user_mcp_servers>\n");
        return builder.toString();
    }

    public String callTool(Integer studentId, String serverKey, String toolName, String argumentsJson) throws Exception {
        AgentMcpServer server = findEnabled(studentId, serverKey);
        if (server == null) {
            throw new IllegalArgumentException("MCP server not found or disabled");
        }
        if (!"http".equalsIgnoreCase(server.getTransport())) {
            throw new IllegalArgumentException("Only HTTP JSON-RPC MCP endpoints are supported");
        }
        URI endpoint = validateEndpoint(server.getEndpoint());
        JsonElement arguments = parseArguments(argumentsJson);
        JsonObject payload = new JsonObject();
        payload.addProperty("jsonrpc", "2.0");
        payload.addProperty("id", UUID.randomUUID().toString());
        payload.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments);
        payload.add("params", params);

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));
        if (server.getAuthHeader() != null && !server.getAuthHeader().isBlank()) {
            request.header("Authorization", server.getAuthHeader());
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return "http_status=" + response.statusCode() + "\n" + limit(response.body(), 60000);
    }

    private void applyBody(AgentMcpServer server, Integer studentId, Map<String, Object> body, boolean creating) {
        if (creating) {
            server.setStudentId(studentId);
            server.setStatus(1);
            server.setCreateTime(LocalDateTime.now());
        }
        String key = normalizeKey(string(body, "serverKey", creating ? "" : server.getServerKey()));
        String name = string(body, "serverName", creating ? "" : server.getServerName());
        String transport = string(body, "transport", "http");
        String endpoint = string(body, "endpoint", creating ? "" : server.getEndpoint());
        String authHeader = string(body, "authHeader", server.getAuthHeader());
        if (!creating && (authHeader == null || authHeader.isBlank())) {
            authHeader = server.getAuthHeader();
        }
        String toolsJson = string(body, "toolsJson", server.getToolsJson());
        Integer enabled = bool(body, "isEnabled", server.getIsEnabled() == null ? 1 : server.getIsEnabled());

        if (key.isBlank()) {
            key = normalizeKey(name);
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("Server key is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Server name is required");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Endpoint is required");
        }
        validateEndpoint(endpoint);
        if (!"http".equalsIgnoreCase(transport)) {
            throw new IllegalArgumentException("Only HTTP transport is supported");
        }
        if (toolsJson != null && toolsJson.length() > MAX_TOOLS_JSON) {
            throw new IllegalArgumentException("Tools manifest is too long");
        }

        server.setServerKey(key);
        server.setServerName(limit(name.trim(), 120));
        server.setTransport("http");
        server.setEndpoint(endpoint.trim());
        server.setAuthHeader(authHeader == null || authHeader.isBlank() ? null : limit(authHeader.trim(), 600));
        server.setToolsJson(toolsJson == null ? "" : toolsJson.trim());
        server.setIsEnabled(enabled);
        server.setUpdateTime(LocalDateTime.now());
    }

    private URI validateEndpoint(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                throw new IllegalArgumentException("Endpoint must use http or https");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Endpoint must not contain user info");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank() || isBlockedHost(host)) {
                throw new IllegalArgumentException("Endpoint host is not allowed");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid endpoint");
        }
    }

    private boolean isBlockedHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.equals("0.0.0.0") || h.equals("::1")) {
            return true;
        }
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) {
            return true;
        }
        if (h.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")) {
            return true;
        }
        return h.equals("metadata.google.internal") || h.equals("169.254.169.254");
    }

    private JsonElement parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(argumentsJson);
        return parsed.isJsonObject() ? parsed : new JsonObject();
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        String key = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        key = key.replaceAll("^-+", "").replaceAll("-+$", "");
        return key.length() <= 80 ? key : key.substring(0, 80);
    }

    private String string(Map<String, Object> body, String key, String fallback) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) {
            return fallback == null ? "" : fallback;
        }
        return String.valueOf(body.get(key)).trim();
    }

    private Integer bool(Map<String, Object> body, String key, Integer fallback) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) {
            return fallback == null ? 1 : fallback;
        }
        Object value = body.get(key);
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String raw = String.valueOf(value).trim();
        return "1".equals(raw) || "true".equalsIgnoreCase(raw) ? 1 : 0;
    }

    private String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
