package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentRunArtifact;
import com.labex.mapper.AgentRunArtifactMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunArtifactService {
    private final AgentRunArtifactMapper mapper;
    private final AgentRunExecutionLeaseService leaseService;

    @Autowired
    public AgentRunArtifactService(AgentRunArtifactMapper mapper, AgentRunExecutionLeaseService leaseService) {
        this.mapper = mapper;
        this.leaseService = leaseService;
    }

    public AgentRunArtifactService(AgentRunArtifactMapper mapper) {
        this(mapper, null);
    }

    public AgentRunArtifact record(Long taskId, String type, String path, String content) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("taskId must be positive");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("artifact type is required");
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setTaskId(taskId); artifact.setArtifactType(type.trim()); artifact.setArtifactPath(path);
        artifact.setContent(content == null ? "" : content); artifact.setSha256(sha256(artifact.getContent())); artifact.setCreateTime(LocalDateTime.now());
        mapper.insert(artifact); return artifact;
    }

    /**
     * Executor-fenced artifact 写入：先验证 {@link ExecutionFence}（owner + 精确 epoch + 未过期 lease），
     * stale fence 抛出 typed failure，artifact 行不被写入。
     * 该预检在写入事务内、任何 INSERT/UPDATE 之前执行；lifecycle/plan 的 fenced 写入另将
     * owner/epoch/active-lease 嵌入 UPDATE 谓词。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunArtifact record(ExecutionFence fence, Long taskId, String type, String path, String content) {
        requireFence(fence);
        return record(taskId, type, path, content);
    }

    public AgentRunArtifact recordDeterministic(Long taskId, String type, String path, String content) {
        String safeContent = content == null ? "" : content;
        String digest = sha256(safeContent);
        AgentRunArtifact existing = mapper.selectOne(new LambdaQueryWrapper<AgentRunArtifact>()
                .eq(AgentRunArtifact::getTaskId, taskId)
                .eq(AgentRunArtifact::getArtifactType, type)
                .eq(path != null, AgentRunArtifact::getArtifactPath, path)
                .eq(AgentRunArtifact::getSha256, digest)
                .orderByDesc(AgentRunArtifact::getArtifactId)
                .last("LIMIT 1"));
        return existing != null ? existing : record(taskId, type, path, safeContent);
    }

    /**
     * Executor-fenced deterministic artifact 写入：先验证 {@link ExecutionFence}，stale fence 抛出 typed failure。
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentRunArtifact recordDeterministic(ExecutionFence fence, Long taskId, String type,
                                                String path, String content) {
        requireFence(fence);
        return recordDeterministic(taskId, type, path, content);
    }

    /**
     * 将完整输出登记为 durable artifact 索引，只保存 workspace 相对路径和摘要，
     * 不把完整 stdout/stderr 复制到普通日志。
     */
    public AgentRunArtifact recordToolOutput(ExecutionFence fence, Long taskId, String toolName, String toolCallId,
                                             String outputPath, String shell, String workdir, String status,
                                             Integer exitCode, long durationMs, boolean truncated, long outputChars) {
        String content = "tool=" + safeValue(toolName)
                + "\ntool_call_id=" + safeValue(toolCallId)
                + "\nshell=" + safeValue(shell)
                + "\nworkdir=" + safeValue(workdir)
                + "\nstatus=" + safeValue(status)
                + "\nexit=" + (exitCode == null ? "none" : exitCode)
                + "\nduration_ms=" + Math.max(0L, durationMs)
                + "\ntruncated=" + truncated
                + "\noutput_chars=" + Math.max(0L, outputChars);
        return fence == null
                ? recordDeterministic(taskId, "tool_output", outputPath, content)
                : recordDeterministic(fence, taskId, "tool_output", outputPath, content);
    }

    private String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    public AgentRunArtifact latest(Long taskId, String type) {
        if (taskId == null || type == null || type.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<AgentRunArtifact>()
                .eq(AgentRunArtifact::getTaskId, taskId)
                .eq(AgentRunArtifact::getArtifactType, type)
                .orderByDesc(AgentRunArtifact::getArtifactId)
                .last("LIMIT 1"));
    }

    public java.util.List<AgentRunArtifact> listAll(Long taskId) {
        if (taskId == null) return java.util.List.of();
        return mapper.selectList(new LambdaQueryWrapper<AgentRunArtifact>()
                .eq(AgentRunArtifact::getTaskId, taskId)
                .orderByAsc(AgentRunArtifact::getArtifactId));
    }
    public java.util.List<AgentRunArtifact> list(Long taskId, String type) {
        if (taskId == null || type == null || type.isBlank()) return java.util.List.of();
        return mapper.selectList(new LambdaQueryWrapper<AgentRunArtifact>()
                .eq(AgentRunArtifact::getTaskId, taskId)
                .eq(AgentRunArtifact::getArtifactType, type)
                .orderByAsc(AgentRunArtifact::getArtifactId));
    }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private void requireFence(ExecutionFence fence) {
        if (leaseService == null) {
            throw new IllegalStateException("ExecutionFence support is not configured");
        }
        if (fence == null) {
            throw new IllegalStateException("ExecutionFence is required for executor-originated writes");
        }
        leaseService.requireActiveFence(fence, LocalDateTime.now());
    }
}
