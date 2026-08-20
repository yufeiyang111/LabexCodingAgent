package com.labex.auth.oauth;

/** 第三方 Provider 返回的最小可信身份资料。 */
public record OAuthIdentity(String provider, String subject, String email, String displayName, String avatarUrl) {
}
