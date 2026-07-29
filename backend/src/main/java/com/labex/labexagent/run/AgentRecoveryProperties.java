package com.labex.labexagent.run;

import com.labex.labexagent.commandsecurity.VerificationStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Agent 自动恢复与验证策略配置。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.recovery")
public class AgentRecoveryProperties {
    private boolean autoRepairEnabled = true;
    private int maxAutomaticRepairs = 3;
    private int maxRepeatedCommandFailures = 2;
    private int maxEnvironmentFailures = 1;
    private VerificationStrategy verificationStrategy = VerificationStrategy.AUTO;
    private VerificationStrategy fallbackVerificationStrategy = VerificationStrategy.MANUAL;

    public boolean isAutoRepairEnabled() { return autoRepairEnabled; }
    public void setAutoRepairEnabled(boolean value) { autoRepairEnabled = value; }
    public int getMaxAutomaticRepairs() { return maxAutomaticRepairs; }
    public void setMaxAutomaticRepairs(int value) { maxAutomaticRepairs = Math.max(0, Math.min(10, value)); }
    public int getMaxRepeatedCommandFailures() { return maxRepeatedCommandFailures; }
    public void setMaxRepeatedCommandFailures(int value) { maxRepeatedCommandFailures = Math.max(1, Math.min(10, value)); }
    public int getMaxEnvironmentFailures() { return maxEnvironmentFailures; }
    public void setMaxEnvironmentFailures(int value) { maxEnvironmentFailures = Math.max(1, Math.min(3, value)); }
    public VerificationStrategy getVerificationStrategy() { return verificationStrategy; }
    public void setVerificationStrategy(VerificationStrategy value) { verificationStrategy = value == null ? VerificationStrategy.AUTO : value; }
    public VerificationStrategy getFallbackVerificationStrategy() { return fallbackVerificationStrategy; }
    public void setFallbackVerificationStrategy(VerificationStrategy value) { fallbackVerificationStrategy = value == null ? VerificationStrategy.MANUAL : value; }
}