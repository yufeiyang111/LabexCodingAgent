package com.labex.controller;

import com.labex.common.Result;
import com.labex.service.AuthService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody AuthRequest request) {
        try {
            return Result.success(authService.register(
                    request == null ? null : request.username,
                    request == null ? null : request.password,
                    request == null ? null : request.displayName));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody AuthRequest request) {
        try {
            return Result.success(authService.login(
                    request == null ? null : request.username,
                    request == null ? null : request.password));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/userinfo")
    public Result<Map<String, Object>> userInfo(Authentication auth) {
        try {
            return Result.success(authService.currentUser(Integer.parseInt(auth.getName())));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    public static class AuthRequest {
        public String username;
        public String password;
        public String displayName;
    }
}
