package com.labex.labexagent.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 会话热缓存参数；Redis 只保存可重建的派生数据。 */
@ConfigurationProperties(prefix = "labex-agent.conversation-cache")
public class ConversationCacheProperties {
    private boolean enabled = true;
    private Duration listTtl = Duration.ofSeconds(120);
    private Duration historyTtl = Duration.ofSeconds(60);
    private Duration contextStatusTtl = Duration.ofSeconds(30);
    private long doubleDeleteDelayMs = 500L;
    private String keyPrefix = "labex:conversation";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getListTtl() { return listTtl; }
    public void setListTtl(Duration listTtl) { this.listTtl = listTtl; }
    public Duration getHistoryTtl() { return historyTtl; }
    public void setHistoryTtl(Duration historyTtl) { this.historyTtl = historyTtl; }
    public Duration getContextStatusTtl() { return contextStatusTtl; }
    public void setContextStatusTtl(Duration contextStatusTtl) { this.contextStatusTtl = contextStatusTtl; }
    public long getDoubleDeleteDelayMs() { return doubleDeleteDelayMs; }
    public void setDoubleDeleteDelayMs(long doubleDeleteDelayMs) { this.doubleDeleteDelayMs = doubleDeleteDelayMs; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
}
