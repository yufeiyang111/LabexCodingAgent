package com.labex.labexagent.attachment;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Agent 输入附件配置；与 application.yml 的 labex-agent.attachment 前缀对应。 */
@Component
@ConfigurationProperties(prefix = "labex-agent.attachment")
public class AgentAttachmentProperties {
    private String storagePath = "workspaces/agent-input-attachments";
    private int maxFilesPerMessage = 4;
    private long maxFileSizeBytes = 20L * 1024L * 1024L;
    private long maxTotalSizeBytes = 40L * 1024L * 1024L;
    private Set<String> allowedMimeTypes = new LinkedHashSet<>(Set.of("image/jpeg", "image/png", "image/gif", "image/webp"));
    private long pendingTtlMinutes = 15;
    private long terminalTtlHours = 24;
    private long cleanupIntervalMs = 900_000;
    private int cleanupBatchSize = 100;
    private int estimatedImageTokens = 8_192;
    public String getStoragePath(){return storagePath;} public void setStoragePath(String v){storagePath=v;}
    public int getMaxFilesPerMessage(){return maxFilesPerMessage;} public void setMaxFilesPerMessage(int v){maxFilesPerMessage=v;}
    public long getMaxFileSizeBytes(){return maxFileSizeBytes;} public void setMaxFileSizeBytes(long v){maxFileSizeBytes=v;}
    public long getMaxTotalSizeBytes(){return maxTotalSizeBytes;} public void setMaxTotalSizeBytes(long v){maxTotalSizeBytes=v;}
    public Set<String> getAllowedMimeTypes(){return allowedMimeTypes;} public void setAllowedMimeTypes(Set<String> v){allowedMimeTypes=v==null?Set.of():new LinkedHashSet<>(v);}
    public long getPendingTtlMinutes(){return pendingTtlMinutes;} public void setPendingTtlMinutes(long v){pendingTtlMinutes=v;}
    public long getTerminalTtlHours(){return terminalTtlHours;} public void setTerminalTtlHours(long v){terminalTtlHours=v;}
    public long getCleanupIntervalMs(){return cleanupIntervalMs;} public void setCleanupIntervalMs(long v){cleanupIntervalMs=v;}
    public int getCleanupBatchSize(){return cleanupBatchSize;} public void setCleanupBatchSize(int v){cleanupBatchSize=v;}
    public int getEstimatedImageTokens(){return estimatedImageTokens;} public void setEstimatedImageTokens(int v){estimatedImageTokens=v;}
}
