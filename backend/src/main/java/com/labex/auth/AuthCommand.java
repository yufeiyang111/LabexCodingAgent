package com.labex.auth;

/** 登录/注册请求的认证域输入，不让领域服务依赖 Web DTO。 */
public record AuthCommand(
        String username,
        String email,
        String displayName,
        String password,
        String captchaId,
        String captchaCode) {

    public static AuthCommand login(String username, String password, String captchaId, String captchaCode) {
        return new AuthCommand(username, null, null, password, captchaId, captchaCode);
    }

    public static AuthCommand register(String username, String email, String displayName, String password,
                                       String captchaId, String captchaCode) {
        return new AuthCommand(username, email, displayName, password, captchaId, captchaCode);
    }
}
