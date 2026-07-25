package com.labex.labexagent.run;

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

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
