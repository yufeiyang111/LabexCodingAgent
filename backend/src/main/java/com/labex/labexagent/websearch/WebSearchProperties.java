package com.labex.labexagent.websearch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "labex-agent.web-search")
public class WebSearchProperties {
    private String provider = "auto";
    private boolean tavilyEnabled = true;
    private boolean exaEnabled = true;
    private boolean parallelEnabled;
    private boolean publicFallbackEnabled = true;
    private String tavilyApiKey;
    private String exaApiKey;
    private String parallelApiKey;
    private int requestTimeoutSeconds = 180;
    private int maxResponseBytes = 1_048_576;
    private boolean cacheEnabled = true;
    private int cacheTtlSeconds = 600;
    private int cacheMaxEntries = 100;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public boolean isTavilyEnabled() { return tavilyEnabled; }
    public void setTavilyEnabled(boolean tavilyEnabled) { this.tavilyEnabled = tavilyEnabled; }
    public boolean isExaEnabled() { return exaEnabled; }
    public void setExaEnabled(boolean exaEnabled) { this.exaEnabled = exaEnabled; }
    public boolean isParallelEnabled() { return parallelEnabled; }
    public void setParallelEnabled(boolean parallelEnabled) { this.parallelEnabled = parallelEnabled; }
    public boolean isPublicFallbackEnabled() { return publicFallbackEnabled; }
    public void setPublicFallbackEnabled(boolean publicFallbackEnabled) { this.publicFallbackEnabled = publicFallbackEnabled; }
    public String getTavilyApiKey() { return tavilyApiKey; }
    public void setTavilyApiKey(String tavilyApiKey) { this.tavilyApiKey = tavilyApiKey; }
    public String getExaApiKey() { return exaApiKey; }
    public void setExaApiKey(String exaApiKey) { this.exaApiKey = exaApiKey; }
    public String getParallelApiKey() { return parallelApiKey; }
    public void setParallelApiKey(String parallelApiKey) { this.parallelApiKey = parallelApiKey; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) { this.requestTimeoutSeconds = requestTimeoutSeconds; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public boolean isCacheEnabled() { return cacheEnabled; }
    public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; }
    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public int getCacheMaxEntries() { return cacheMaxEntries; }
    public void setCacheMaxEntries(int cacheMaxEntries) { this.cacheMaxEntries = cacheMaxEntries; }
}
