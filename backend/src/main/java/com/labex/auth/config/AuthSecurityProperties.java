package com.labex.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 认证模块可调参数集中配置。 */
@Data
@ConfigurationProperties(prefix = "labex-agent.auth")
public class AuthSecurityProperties {
    private String redisUrl = "";
    private int loginRateLimit = 10;
    private int oauthExchangeRateLimit = 10;
    private int registerRateLimit = 5;
    private int captchaRateLimit = 20;
    private int loginFailureThreshold = 3;
    private int registerCaptchaThreshold = 3;
    private int failureWindowSeconds = 300;
    private int captchaTtlSeconds = 120;
    private int captchaLength = 5;
    private int captchaWidth = 128;
    private int captchaHeight = 44;
    private int captchaNoiseLines = 7;
    private int captchaNoiseDots = 70;
    private int captchaFontSize = 26;
    private int oauthStateTtlSeconds = 300;
    private int oauthCodeTtlSeconds = 120;
    private int oauthHttpTimeoutSeconds = 10;
    private String oauthCallbackBaseUrl = "http://localhost:8080/api/auth/oauth";
    private String oauthFrontendCallback = "http://localhost:3000/login";
    private String oauthBindingFrontendCallback = "";
    private String githubClientId = "";
    private String githubClientSecret = "";
    private String googleClientId = "";
    private String googleClientSecret = "";
    private boolean inviteCodeEnabled = false;
    private String inviteCodes = "LABEX-AGENT-2026";
}

