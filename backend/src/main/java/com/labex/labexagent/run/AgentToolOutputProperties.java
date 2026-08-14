package com.labex.labexagent.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for bounded durable tool-output reads. */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.tool-output")
public class AgentToolOutputProperties {
    /** Maximum UTF-16 characters returned by one read_tool_output page. */
    private int readMaxChars = 4_000;

    public int getReadMaxChars() {
        return readMaxChars;
    }

    public void setReadMaxChars(int value) {
        readMaxChars = Math.max(256, Math.min(20_000, value));
    }
}
