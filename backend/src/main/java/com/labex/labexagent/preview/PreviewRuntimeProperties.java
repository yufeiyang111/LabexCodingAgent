package com.labex.labexagent.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 预览服务启动与就绪探测的集中配置。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.preview")
public class PreviewRuntimeProperties {
    private boolean enabled = true;
    private int startupTimeoutMs = 30_000;
    private int pollIntervalMs = 250;
    private int connectTimeoutMs = 1_000;
    private int outputMaxChars = 60_000;
    private int failureHintMaxChars = 512;
    private int outputDrainTimeoutMs = 1_000;
    private int minPort = 1024;
    private int maxPort = 65_535;
    private String readinessHost = "127.0.0.1";
    private String publicHost = "localhost";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getStartupTimeoutMs() { return Math.max(1_000, startupTimeoutMs); }
    public void setStartupTimeoutMs(int value) { startupTimeoutMs = value; }
    public int getPollIntervalMs() { return Math.max(10, pollIntervalMs); }
    public void setPollIntervalMs(int value) { pollIntervalMs = value; }
    public int getConnectTimeoutMs() { return Math.max(100, connectTimeoutMs); }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
    public int getOutputMaxChars() { return Math.max(1_024, outputMaxChars); }
    public void setOutputMaxChars(int value) { outputMaxChars = value; }
    public int getFailureHintMaxChars() { return Math.max(64, failureHintMaxChars); }
    public void setFailureHintMaxChars(int value) { failureHintMaxChars = value; }
    public int getOutputDrainTimeoutMs() { return Math.max(100, outputDrainTimeoutMs); }
    public void setOutputDrainTimeoutMs(int value) { outputDrainTimeoutMs = value; }
    public int getMinPort() { return Math.max(1, Math.min(minPort, getMaxPort())); }
    public void setMinPort(int value) { minPort = value; }
    public int getMaxPort() { return Math.max(1, Math.min(65_535, maxPort)); }
    public void setMaxPort(int value) { maxPort = value; }
    public String getReadinessHost() { return host(readinessHost, "127.0.0.1"); }
    public void setReadinessHost(String value) { readinessHost = value; }
    public String getPublicHost() { return host(publicHost, "localhost"); }
    public void setPublicHost(String value) { publicHost = value; }

    private String host(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains("@")
                || normalized.contains("\r") || normalized.contains("\n")) {
            return fallback;
        }
        return normalized;
    }
}