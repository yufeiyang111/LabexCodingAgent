package com.labex.controller;

import com.labex.auth.AuthApplicationService;
import com.labex.auth.AuthCommand;
import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.captcha.CaptchaService;
import com.labex.auth.config.AuthSecurityProperties;
import com.labex.auth.oauth.OAuthApplicationService;
import com.labex.auth.redis.AuthRedisUnavailableException;
import com.labex.common.Result;
import com.labex.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthApplicationService authApplicationService;
    private final AuthService authService;
    private final CaptchaService captchaService;
    private final OAuthApplicationService oauthApplicationService;
    private final AuthSecurityProperties authSecurityProperties;

    public AuthController(AuthApplicationService authApplicationService, AuthService authService,
                          CaptchaService captchaService, OAuthApplicationService oauthApplicationService,
                          AuthSecurityProperties authSecurityProperties) {
        this.authApplicationService = authApplicationService;
        this.authService = authService;
        this.captchaService = captchaService;
        this.oauthApplicationService = oauthApplicationService;
        this.authSecurityProperties = authSecurityProperties;
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody AuthRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse response) {
        noStore(response);
        try {
            AuthRequest value = request == null ? new AuthRequest() : request;
            return Result.success(authApplicationService.register(
                    AuthCommand.register(value.username, value.email, value.displayName, value.password,
                            value.captchaId, value.captchaCode), source(httpRequest)));
        } catch (Exception failure) {
            return errorResult(failure, response);
        }
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody AuthRequest request,
                                             HttpServletRequest httpRequest,
                                             HttpServletResponse response) {
        noStore(response);
        try {
            AuthRequest value = request == null ? new AuthRequest() : request;
            if (StringUtils.hasText(value.oauthCode)) {
                authApplicationService.checkOAuthExchange(source(httpRequest));
                return Result.success(oauthApplicationService.exchange(value.oauthCode));
            }
            return Result.success(authApplicationService.login(
                    AuthCommand.login(value.username, value.password, value.captchaId, value.captchaCode),
                    source(httpRequest)));
        } catch (Exception failure) {
            return errorResult(failure, response);
        }
    }

    @GetMapping("/captcha")
    public Result<Map<String, Object>> captcha(@RequestParam(defaultValue = "login") String scene,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {
        noStore(response);
        try {
            return Result.success(captchaService.create(scene, source(request)));
        } catch (Exception failure) {
            return errorResult(failure, response);
        }
    }

    @GetMapping("/config")
    public Result<Map<String, Object>> authConfig(HttpServletResponse response) {
        noStore(response);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("inviteCodeEnabled", authApplicationService.isInviteCodeEnabled());
        config.put("oauthProviders", oauthApplicationService.enabledProviders());
        return Result.success(config);
    }

    @PostMapping("/invite-register")
    public Result<Map<String, Object>> inviteRegister(@RequestBody AuthRequest request,
                                                      HttpServletRequest httpRequest,
                                                      HttpServletResponse response) {
        noStore(response);
        try {
            AuthRequest value = request == null ? new AuthRequest() : request;
            return Result.success(authApplicationService.inviteRegister(
                    AuthCommand.register(value.username, value.email, value.displayName, value.password,
                            value.captchaId, value.captchaCode),
                    value.inviteCode,
                    source(httpRequest)));
        } catch (Exception failure) {
            return errorResult(failure, response);
        }
    }

    @GetMapping("/oauth/providers")
    public Result<Map<String, Object>> oauthProviders(HttpServletResponse response) {
        noStore(response);
        return Result.success(oauthApplicationService.enabledProviders());
    }

    @GetMapping("/oauth/{provider}/authorize")
    public ResponseEntity<Void> oauthAuthorize(@PathVariable String provider,
                                                HttpServletRequest request,
                                                HttpServletResponse response) {
        noStore(response);
        try {
            OAuthApplicationService.AuthorizationRequest authorization = oauthApplicationService.authorizationUrl(provider);
            response.setHeader(HttpHeaders.SET_COOKIE, oauthStateCookie(authorization.state(), request, false));
            return redirect(authorization.url());
        } catch (Exception failure) {
            log.warn("OAuth authorize failed provider={} errorCode={} errorType={}", provider, oauthErrorCode(failure), failure.getClass().getSimpleName());
            return redirectError(failure);
        }
    }

    @GetMapping("/oauth/{provider}/bind/authorize")
    public ResponseEntity<Void> oauthBindAuthorize(@PathVariable String provider,
                                                   Authentication authentication,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        noStore(response);
        try {
            if (authentication == null || !StringUtils.hasText(authentication.getName())) {
                throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "请先登录本地账号");
            }
            OAuthApplicationService.AuthorizationRequest authorization = oauthApplicationService
                    .bindingAuthorizationUrl(provider, Integer.parseInt(authentication.getName()));
            response.setHeader(HttpHeaders.SET_COOKIE, oauthStateCookie(authorization.state(), request, false));
            return redirect(authorization.url());
        } catch (Exception failure) {
            log.warn("OAuth binding authorization failed provider={} errorCode={} errorType={}", provider, oauthErrorCode(failure), failure.getClass().getSimpleName());
            return redirectError(failure);
        }
    }

    @PostMapping("/oauth/{provider}/bind/start")
    public Result<Map<String, Object>> oauthBindStart(@PathVariable String provider,
                                                        Authentication authentication,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        noStore(response);
        try {
            if (authentication == null || !StringUtils.hasText(authentication.getName())) {
                throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "请先登录本地账号");
            }
            OAuthApplicationService.AuthorizationRequest authorization = oauthApplicationService
                    .bindingAuthorizationUrl(provider, Integer.parseInt(authentication.getName()));
            response.setHeader(HttpHeaders.SET_COOKIE, oauthStateCookie(authorization.state(), request, false));
            return Result.success(Map.of("authorizationUrl", authorization.url()));
        } catch (Exception failure) {
            log.warn("OAuth binding start failed provider={} errorCode={} errorType={}", provider,
                    oauthErrorCode(failure), failure.getClass().getSimpleName());
            return errorResult(failure, response);
        }
    }

    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<Void> oauthCallback(@PathVariable String provider,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String code,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        noStore(response);
        try {
            String cookieState = readCookie(request, "labex_oauth_state");
            if (!StringUtils.hasText(state) || !state.equals(cookieState)) {
                throw new AuthException(AuthErrorCode.OAUTH_STATE_INVALID, "第三方授权状态无效，请重试");
            }
            response.setHeader(HttpHeaders.SET_COOKIE, oauthStateCookie("", request, true));
            return redirect(oauthApplicationService.callback(provider, state, code));
        } catch (Exception failure) {
            log.warn("OAuth callback failed provider={} errorCode={} errorType={}", provider, oauthErrorCode(failure), failure.getClass().getSimpleName());
            response.setHeader(HttpHeaders.SET_COOKIE, oauthStateCookie("", request, true));
            return redirectError(failure);
        }
    }

    @GetMapping("/oauth/bindings")
    public Result<Map<String, Object>> oauthBindings(Authentication authentication,
                                                       HttpServletResponse response) {
        noStore(response);
        try {
            if (authentication == null || !StringUtils.hasText(authentication.getName())) {
                throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "请先登录本地账号");
            }
            return Result.success(oauthApplicationService
                    .listBindings(Integer.parseInt(authentication.getName())));
        } catch (Exception failure) {
            return errorResult(failure, response);
        }
    }

    @DeleteMapping("/oauth/{provider}/binding")
    public Result<Map<String, Object>> oauthUnbind(@PathVariable String provider,
                                                    Authentication authentication,
                                                    HttpServletResponse response) {
        noStore(response);
        try {
            if (authentication == null || !StringUtils.hasText(authentication.getName())) {
                throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "请先登录本地账号");
            }
            return Result.success(oauthApplicationService
                    .unbind(Integer.parseInt(authentication.getName()), provider));
        } catch (Exception failure) {
            return errorResult(failure, response);
        }
    }

    @GetMapping("/userinfo")
    public Result<Map<String, Object>> userInfo(Authentication auth, HttpServletResponse response) {
        noStore(response);
        try {
            if (auth == null || !StringUtils.hasText(auth.getName())) {
                throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "登录已失效，请重新登录");
            }
            return Result.success(authService.currentUser(Integer.parseInt(auth.getName())));
        } catch (Exception failure) {
            return errorResult(failure, response);
        }
    }

    private ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, location).build();
    }

    private ResponseEntity<Void> redirectError(Exception failure) {
        return redirect(oauthApplicationService.errorRedirect(failure));
    }

    private Result<Map<String, Object>> errorResult(Exception failure, HttpServletResponse response) {
        if (containsRedisFailure(failure)) {
            response.setStatus(503);
            return Result.error(AuthErrorCode.REDIS_UNAVAILABLE, "认证服务暂时不可用，请稍后重试");
        }
        if (failure instanceof AuthException auth) {
            if (auth.getCode() == AuthErrorCode.RATE_LIMITED) {
                response.setStatus(429);
            }
            if (auth.getCode() == AuthErrorCode.REDIS_UNAVAILABLE) {
                response.setStatus(503);
            }
            if (auth.getRetryAfterSeconds() > 0) {
                response.setHeader("Retry-After", String.valueOf(auth.getRetryAfterSeconds()));
            }
            Map<String, Object> details = new LinkedHashMap<>(auth.getDetails());
            details.putIfAbsent("errorCode", auth.getCode());
            Result<Map<String, Object>> result = Result.error(auth.getCode(), auth.getMessage());
            result.setData(details);
            return result;
        }
        return Result.error(AuthErrorCode.VALIDATION_FAILED, "认证请求无效，请检查输入");
    }

    private int oauthErrorCode(Exception failure) {
        if (failure instanceof AuthException auth) {
            return auth.getCode();
        }
        return failure instanceof AuthRedisUnavailableException ? AuthErrorCode.REDIS_UNAVAILABLE : -1;
    }

    private boolean containsRedisFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthRedisUnavailableException) {
                return true;
            }
        }
        return false;
    }

    private String source(HttpServletRequest request) {
        return request == null || request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
    }

    private String oauthStateCookie(String value, HttpServletRequest request, boolean clear) {
        return ResponseCookie.from("labex_oauth_state", value == null ? "" : value)
                .httpOnly(true)
                .secure(isSecureRequest(request))
                .sameSite("Lax")
                .path((request == null || !StringUtils.hasText(request.getContextPath()) ? "" : request.getContextPath())
                        + "/auth/oauth")
                .maxAge(clear ? 0 : Math.max(1, authSecurityProperties.getOauthStateTtlSeconds()))
                .build()
                .toString();
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        return request != null && (request.isSecure()
                || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public static class AuthRequest {
        public String username;
        public String email;
        public String password;
        public String displayName;
        public String captchaId;
        public String captchaCode;
        public String oauthCode;
        public String inviteCode;
    }

}





