package com.labex.labexagent.mcp;

import com.google.gson.JsonObject;
import com.labex.entity.AgentMcpServer;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.service.AgentMcpServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 管理器
 * 管理学生的 MCP 客户端实例，提供工具发现和调用
 */
@Slf4j
@Service
public class McpManager {

    private final AgentMcpServerService mcpServerService;
    private final SandboxWorker sandboxWorker;
    private final OutboundUrlPolicy outboundUrlPolicy;

    // studentId -> serverKey -> McpClient
    private final Map<Integer, Map<String, McpClient>> clientMap = new ConcurrentHashMap<>();

    public McpManager(
            AgentMcpServerService mcpServerService,
            SandboxWorker sandboxWorker,
            OutboundUrlPolicy outboundUrlPolicy) {
        this.mcpServerService = mcpServerService;
        this.sandboxWorker = sandboxWorker;
        this.outboundUrlPolicy = outboundUrlPolicy;
    }

    /**
     * 获取或创建学生的 MCP 客户端
     */
    public McpClient getClient(Integer studentId, String serverKey) throws IOException {
        return getClient(studentId, null, serverKey);
    }

    public McpClient getClient(Integer studentId, WorkerRunSpec workerRun, String serverKey) throws IOException {
        Map<String, McpClient> clients = clientMap.computeIfAbsent(studentId, k -> new ConcurrentHashMap<>());
        String clientKey = clientKey(serverKey, workerRun);
        McpClient client = clients.get(clientKey);

        if (client != null && client.isConnected()) {
            return client;
        }

        // 从数据库加载配置
        AgentMcpServer server = mcpServerService.findEnabled(studentId, serverKey);
        if (server == null) {
            throw new IOException("MCP server not found: " + serverKey);
        }

        if (server.getIsEnabled() != null && server.getIsEnabled() == 0) {
            throw new IOException("MCP server is disabled: " + serverKey);
        }

        // 创建新客户端
        client = McpClient.withAuthHeaderSupplier(
            serverKey,
            server.getTransport() != null ? server.getTransport() : "http",
            server.getEndpoint(),
            () -> {
                AgentMcpServer current = mcpServerService.findEnabled(studentId, serverKey);
                return current == null ? "" : mcpServerService.resolveAuthHeader(current);
            },
            null,
            sandboxWorker,
            workerRun,
            outboundUrlPolicy
        );

        client.connect();
        clients.put(clientKey, client);

        return client;
    }

    /**
     * 调用 MCP 工具
     */
    public McpClient.CallResult callTool(Integer studentId, String serverKey, String toolName, String argumentsJson) {
        return callTool(studentId, null, serverKey, toolName, argumentsJson);
    }

    public McpClient.CallResult callTool(
            Integer studentId, WorkerRunSpec workerRun, String serverKey, String toolName, String argumentsJson) {
        try {
            McpClient client = getClient(studentId, workerRun, serverKey);
            return client.callTool(toolName, argumentsJson);
        } catch (Exception e) {
            log.error("MCP call failed: {}.{}", serverKey, toolName, e);
            return McpClient.CallResult.fail("MCP call failed: " + e.getMessage());
        }
    }

    /**
     * 获取学生所有可用的 MCP 工具
     */
    public List<McpToolAdapter.McpToolInfo> getAvailableTools(Integer studentId) {
        List<McpToolAdapter.McpToolInfo> allTools = new ArrayList<>();

        List<AgentMcpServer> servers = mcpServerService.listByStudent(studentId);
        for (AgentMcpServer server : servers) {
            if (server.getIsEnabled() != null && server.getIsEnabled() == 0) continue;

            try {
                McpClient client = getClient(studentId, server.getServerKey());
                for (McpClient.ToolDef tool : client.getTools()) {
                    allTools.add(new McpToolAdapter.McpToolInfo(
                        server.getServerKey(),
                        server.getServerName(),
                        tool.getName(),
                        tool.getDescription(),
                        tool.getInputSchema()
                    ));
                }
            } catch (Exception e) {
                log.debug("Failed to get tools from MCP server '{}': {}", server.getServerKey(), e.getMessage());
            }
        }

        return allTools;
    }

    /**
     * 断开学生的所有 MCP 连接
     */
    public void disconnectAll(Integer studentId) {
        Map<String, McpClient> clients = clientMap.remove(studentId);
        if (clients != null) {
            clients.values().forEach(McpClient::close);
        }
    }

    /**
     * 断开指定服务器连接
     */
    public void disconnect(Integer studentId, String serverKey) {
        Map<String, McpClient> clients = clientMap.get(studentId);
        if (clients != null) {
            clients.entrySet().removeIf(entry -> {
                if (!entry.getKey().startsWith(serverKey + "::")) {
                    return false;
                }
                entry.getValue().close();
                return true;
            });
        }
    }

    /**
     * 重连指定服务器
     */
    public void reconnect(Integer studentId, String serverKey) throws IOException {
        disconnect(studentId, serverKey);
        getClient(studentId, serverKey);
    }

    private String clientKey(String serverKey, WorkerRunSpec workerRun) {
        String workspace = workerRun == null ? "global" : workerRun.workspaceRoot().toString();
        return serverKey + "::" + workspace;
    }
}
