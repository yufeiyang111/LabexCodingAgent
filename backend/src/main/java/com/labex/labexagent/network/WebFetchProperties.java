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