package com.labex.auth.oauth;

/** Redis 中一次性 OAuth code 的可序列化内容。 */
public record OAuthCodePayload(String provider, String subject, String email, String displayName, String avatarUrl) {
}
