package com.labex.labexagent.network;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Web Fetch 的网络与输出资源边界。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.web-fetch")
public class WebFetchProperties {
    private int connectTimeoutSeconds = 20;
    private int requestTimeoutSeconds = 60;
    private int maxRedirects = 5;
    private int maxResponseBytes = 1_048_576;
    private int defaultMaxChars = 12_000;
    private int minMaxChars = 1_000;
    private int maxMaxChars = 50_000;

    /** 可选代理服务器主机，默认尝试自适应环境变量 HTTP_PROXY / HTTPS_PROXY */
    private String proxyHost;
    /** 可选代理服务器端口 */
    private Integer proxyPort;

    /** 是否开启对齐 OpenCode 的 Cloudflare 质询自愈重试机制 */
    private boolean cfRetryEnabled = true;

    /** 是否在直连建连超时或反爬拦截时启用 Reader 备用抓取兜底 */
    private boolean readerFallbackEnabled = true;

    /** 兜底 Reader 服务前缀，默认使用公网高质量 Reader 服务 */
    private String readerEndpoint = "https://r.jina.ai/";

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public Integer getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(Integer proxyPort) {
        this.proxyPort = proxyPort;
    }

    public boolean isCfRetryEnabled() {
        return cfRetryEnabled;
    }

    public void setCfRetryEnabled(boolean cfRetryEnabled) {
        this.cfRetryEnabled = cfRetryEnabled;
    }

    public boolean isReaderFallbackEnabled() {
        return readerFallbackEnabled;
    }

    public void setReaderFallbackEnabled(boolean readerFallbackEnabled) {
        this.readerFallbackEnabled = readerFallbackEnabled;
    }

    public String getReaderEndpoint() {
        return readerEndpoint;
    }

    public void setReaderEndpoint(String readerEndpoint) {
        this.readerEndpoint = readerEndpoint;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getDefaultMaxChars() {
        return defaultMaxChars;
    }

    public void setDefaultMaxChars(int defaultMaxChars) {
        this.defaultMaxChars = defaultMaxChars;
    }

    public int getMinMaxChars() {
        return minMaxChars;
    }

    public void setMinMaxChars(int minMaxChars) {
        this.minMaxChars = minMaxChars;
    }

    public int getMaxMaxChars() {
        return maxMaxChars;
    }

    public void setMaxMaxChars(int maxMaxChars) {
        this.maxMaxChars = maxMaxChars;
    }
}