package com.labex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.labex.entity.AppUser;
import com.labex.mapper.AppUserMapper;
import com.labex.security.JwtUtil;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthService extends ServiceImpl<AppUserMapper, AppUser> {
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public Map<String, Object> register(String username, String password, String displayName) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        if (findByUsername(normalizedUsername) != null) {
            throw new IllegalArgumentException("Username already exists");
        }
        AppUser user = new AppUser();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(StringUtils.hasText(displayName) ? displayName.trim() : normalizedUsername);
        user.setRole("USER");
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        save(user);
        return issueLoginResponse(user);
    }

    public Map<String, Object> login(String username, String password) {
        AppUser user = findByUsername(normalizeUsername(username));
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new IllegalArgumentException("Invalid username or password");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid username or password");
        }
        return issueLoginResponse(user);
    }

    public Map<String, Object> currentUser(Integer userId) {
        AppUser user = getById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new IllegalArgumentException("User not found");
        }
        return publicUser(user);
    }

    private Map<String, Object> issueLoginResponse(AppUser user) {
        String token = jwtUtil.generateToken(user.getUserId(), String.valueOf(user.getUserId()), user.getRole());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("userInfo", publicUser(user));
        return response;
    }

    private Map<String, Object> publicUser(AppUser user) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userId", user.getUserId());
        info.put("username", user.getUsername());
        info.put("displayName", user.getDisplayName());
        info.put("role", user.getRole());
        return info;
    }

    private AppUser findByUsername(String username) {
        return getOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username).last("LIMIT 1"));
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("Username is required");
        }
        String value = username.trim().toLowerCase();
        if (!value.matches("[a-z0-9_.-]{3,32}")) {
            throw new IllegalArgumentException("Username must be 3-32 characters and only contain letters, numbers, underscore, dot or dash");
        }
        return value;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 72) {
            throw new IllegalArgumentException("Password must be 6-72 characters");
        }
    }
}
