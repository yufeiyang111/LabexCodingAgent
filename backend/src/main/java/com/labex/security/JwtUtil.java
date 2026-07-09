package com.labex.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类
 */
@Component
@Slf4j
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    @Value("${jwt.prefix}")
    private String prefix;

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLE = "role";
    private static final int HS512_MIN_KEY_BYTES = 64;

    private SecretKey key;

    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret must not be blank");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < HS512_MIN_KEY_BYTES) {
            log.warn("jwt.secret is {} bytes, shorter than the 64 bytes required by HS512; deriving a 512-bit local signing key. Configure a high-entropy LABEX_AGENT_JWT_SECRET with at least 64 bytes for production.", keyBytes.length);
            keyBytes = sha512(keyBytes);
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    private byte[] sha512(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 digest is not available", e);
        }
    }

    /**
     * 生成Token
     */
    public String generateToken(Integer userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_USERNAME, username);
        claims.put(CLAIM_ROLE, role);

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * 从Token解析用户ID
     */
    public Integer getUserIdFromToken(String token) {
        return Integer.valueOf(getClaimsFromToken(token).get(CLAIM_USER_ID).toString());
    }

    /**
     * 从Token解析用户名
     */
    public String getUsernameFromToken(String token) {
        return getClaimsFromToken(token).get(CLAIM_USERNAME).toString();
    }

    /**
     * 从Token解析角色
     */
    public String getRoleFromToken(String token) {
        return getClaimsFromToken(token).get(CLAIM_ROLE).toString();
    }

    /**
     * 验证Token是否有效
     */
    public Boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("Token已过期: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("Token格式不支持: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("Token格式错误: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("Token签名无效: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Token为空: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从Token获取Claims
     */
    private Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 移除Token前缀
     */
    public String removePrefix(String token) {
        if (token != null && token.startsWith(prefix)) {
            return token.substring(prefix.length());
        }
        return token;
    }

    /**
     * 添加Token前缀
     */
    public String addPrefix(String token) {
        return prefix + token;
    }
}
