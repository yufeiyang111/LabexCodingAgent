package com.labex.auth;

/** 认证模块对外使用的稳定错误码。 */
public final class AuthErrorCode {
    public static final int INVALID_CREDENTIALS = -1001;
    public static final int CAPTCHA_REQUIRED = -1002;
    public static final int CAPTCHA_INVALID = -1003;
    public static final int RATE_LIMITED = -1004;
    public static final int USERNAME_EXISTS = -1005;
    public static final int EMAIL_EXISTS = -1006;
    public static final int VALIDATION_FAILED = -1007;
    public static final int OAUTH_NOT_CONFIGURED = -1008;
    public static final int OAUTH_STATE_INVALID = -1009;
    public static final int OAUTH_CODE_INVALID = -1010;
    public static final int OAUTH_EMAIL_CONFLICT = -1011;
    public static final int OAUTH_BIND_EXPIRED = -1012;
    public static final int REDIS_UNAVAILABLE = -1013;
    public static final int OAUTH_ACCOUNT_NOT_BOUND = -1014;
    public static final int OAUTH_PROVIDER_ALREADY_BOUND = -1015;
    public static final int OAUTH_IDENTITY_ALREADY_BOUND = -1016;
    public static final int OAUTH_LAST_LOGIN_METHOD = -1017;
    public static final int OAUTH_BIND_STATE_INVALID = -1018;
    public static final int OAUTH_PROVIDER_NOT_BOUND = -1019;
    public static final int REGISTRATION_DISABLED = -1020;
    public static final int INVALID_INVITE_CODE = -1021;

    private AuthErrorCode() {
    }
}

