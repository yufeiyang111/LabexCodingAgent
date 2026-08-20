package com.labex.auth.oauth;

public interface OAuthProviderClient {
    String provider();

    boolean enabled();

    String authorizationUrl(String state, String redirectUri);

    OAuthIdentity exchange(String code, String redirectUri);
}
