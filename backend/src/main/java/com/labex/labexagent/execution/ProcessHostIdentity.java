package com.labex.labexagent.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 用于隔离持久化操作系统进程身份的稳定、非敏感宿主指纹。 */
@Component
public class ProcessHostIdentity {
    private final String hostId;

    @org.springframework.beans.factory.annotation.Autowired
    public ProcessHostIdentity(
            @Value("${labex-agent.process-host-id:${LABEX_AGENT_PROCESS_HOST_ID:}}") String configuredHostId) {
        this.hostId = fingerprint(resolveSource(configuredHostId));
    }

    ProcessHostIdentity(String hostId, boolean alreadyFingerprinted) {
        if (hostId == null || hostId.isBlank()) {
            throw new IllegalArgumentException("hostId is required");
        }
        this.hostId = alreadyFingerprinted ? hostId : fingerprint(hostId);
    }

    public String hostId() {
        return hostId;
    }

    static ProcessHostIdentity localDefault() {
        return new ProcessHostIdentity(resolveSource(""), false);
    }

    private static String resolveSource(String configuredHostId) {
        if (configuredHostId != null && !configuredHostId.isBlank()) {
            return configuredHostId.trim();
        }
        String computerName = System.getenv("COMPUTERNAME");
        if (computerName != null && !computerName.isBlank()) {
            return computerName.trim();
        }
        String hostName = System.getenv("HOSTNAME");
        if (hostName != null && !hostName.isBlank()) {
            return hostName.trim();
        }
        return System.getProperty("os.name", "unknown") + "|"
                + System.getProperty("os.arch", "unknown") + "|"
                + System.getProperty("user.home", "unknown");
    }

    private static String fingerprint(String source) {
        String effectiveSource = source == null || source.isBlank() ? resolveSource("") : source;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(effectiveSource.getBytes(StandardCharsets.UTF_8));
            return "host-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
