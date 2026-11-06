package com.labex.labexagent.git;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * clone 用的短期令牌。
 *
 * <p>为什么不用用户 JWT：{@code git clone} 由用户本机的 git 客户端发起，无法附加
 * {@code Authorization} 头；把长期 JWT 塞进 URL 等于把账号凭证写进 shell 历史和远端配置。
 * 这里签发一个只读、限定单个项目、一小时过期的令牌，泄露的代价被时间与范围双重限制。
 *
 * <p>令牌格式 {@code <projectId>.<expiresAtEpochSecond>.<nonce>.<hmac>}，服务端无状态校验；
 * 签名密钥来自 {@code jwt.secret}，与账号令牌同源但用途标签不同（HMAC 输入带 {@code git-clone:} 前缀），
 * 因此两种令牌不能互相冒用。
 */
@Service
public class ProjectGitCloneTokenService {
    private static final Logger log = LoggerFactory.getLogger(ProjectGitCloneTokenService.class);
    private static final String PURPOSE = "git-clone:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectGitProperties properties;
    private final SecretKeySpec signingKey;
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public ProjectGitCloneTokenService(ProjectGitProperties properties,
                                       @Value("${jwt.secret}") String secret) {
        this.properties = properties;
        this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** 为某个项目签发只读 clone 令牌。 */
    public ProjectGitDtos.CloneUrl issue(Integer projectId, String baseUrl) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        Instant expiresAt = Instant.now().plusSeconds(properties.getCloneTokenTtlMinutes() * 60L);
        byte[] nonceBytes = new byte[9];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String payload = projectId + "." + expiresAt.getEpochSecond() + "." + nonce;
        String token = payload + "." + sign(payload);
        String url = trimTrailingSlash(baseUrl) + "/git/" + projectId + ".git";
        return new ProjectGitDtos.CloneUrl(url, token, expiresAt.toString());
    }

    /**
     * 校验令牌是否可用于该项目。返回 true 表示通过。
     *
     * <p>失败原因写进 debug 日志而不是异常：调用方是 git 的 HTTP 请求，返回 401 即可，
     * 不需要区分"过期"和"签名错"，避免成为探测工具。
     */
    public boolean verify(Integer projectId, String token) {
        if (projectId == null || token == null || token.isBlank()) {
            return false;
        }
        if (revoked.containsKey(token)) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        String payload = parts[0] + "." + parts[1] + "." + parts[2];
        if (!constantTimeEquals(sign(payload), parts[3])) {
            log.debug("clone token signature mismatch projectId={}", projectId);
            return false;
        }
        try {
            if (!String.valueOf(projectId).equals(parts[0])) {
                log.debug("clone token project mismatch expected={} actual={}", projectId, parts[0]);
                return false;
            }
            long expiresAt = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() > expiresAt) {
                log.debug("clone token expired projectId={}", projectId);
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 记录已撤销的令牌，直到它自然过期。用于"重新生成 clone 地址"时立刻失效旧链接。 */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String[] parts = token.split("\\.");
        Instant expiry = Instant.now().plusSeconds(properties.getCloneTokenTtlMinutes() * 60L);
        if (parts.length == 4) {
            try {
                expiry = Instant.ofEpochSecond(Long.parseLong(parts[1]));
            } catch (NumberFormatException ignored) {
                // 解析不出过期时间就按默认 TTL 保留，宁可多留一会儿
            }
        }
        revoked.put(token, expiry);
        Instant now = Instant.now();
        revoked.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            byte[] digest = mac.doFinal((PURPOSE + payload).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("clone token signing is unavailable", e);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
