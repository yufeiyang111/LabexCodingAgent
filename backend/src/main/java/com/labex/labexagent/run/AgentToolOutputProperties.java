package com.labex.labexagent.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for durable tool-output pages and model projections. */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.tool-output")
public class AgentToolOutputProperties {
    /** Maximum UTF-16 characters returned by one read_tool_output page. */
    private int readMaxChars = 4_000;
    /** Maximum lines retained in one model-visible tool-output projection. */
    private int modelMaxLines = 2_000;
    /** Maximum UTF-8 bytes retained in one model-visible tool-output projection. */
    private int modelMaxBytes = 50 * 1024;

    public int getReadMaxChars() {
        return readMaxChars;
    }

    public void setReadMaxChars(int value) {
        readMaxChars = Math.max(256, Math.min(20_000, value));
    }

    public int getModelMaxLines() {
        return modelMaxLines;
    }

    public void setModelMaxLines(int value) {
        modelMaxLines = Math.max(1, Math.min(20_000, value));
    }

    public int getModelMaxBytes() {
        return modelMaxBytes;
    }

    public void setModelMaxBytes(int value) {
        modelMaxBytes = Math.max(1_024, Math.min(1_048_576, value));
    }
}
