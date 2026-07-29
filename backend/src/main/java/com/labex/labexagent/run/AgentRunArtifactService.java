package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentRunArtifact;
import com.labex.mapper.AgentRunArtifactMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class AgentRunArtifactService {
    private final AgentRunArtifactMapper mapper;
    public AgentRunArtifactService(AgentRunArtifactMapper mapper) { this.mapper = mapper; }

    public AgentRunArtifact record(Long taskId, String type, String path, String content) {
        if (taskId == null || taskId <= 0) throw new IllegalArgumentException("taskId must be positive");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("artifact type is required");
        AgentRunArtifact artifact = new AgentRunArtifact();
        artifact.setTaskId(taskId); artifact.setArtifactType(type.trim()); artifact.setArtifactPath(path);
        artifact.setContent(content == null ? "" : content); artifact.setSha256(sha256(artifact.getContent())); artifact.setCreateTime(LocalDateTime.now());
        mapper.insert(artifact); return artifact;
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
}
