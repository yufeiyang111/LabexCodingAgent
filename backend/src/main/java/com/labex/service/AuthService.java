package com.labex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.labex.entity.AppUser;
import com.labex.mapper.AppUserMapper;
import com.labex.security.JwtUtil;
import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
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
        return register(username, null, password, displayName);
    }

    @Transactional
    public Map<String, Object> register(String username, String email, String password, String displayName) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        String normalizedEmail = normalizeEmail(email);
        if (findByUsername(normalizedUsername) != null) {
            throw new AuthException(AuthErrorCode.USERNAME_EXISTS, "用户名已被占用");
        }
        if (normalizedEmail != null && findByEmail(normalizedEmail) != null) {
            throw new AuthException(AuthErrorCode.EMAIL_EXISTS, "邮箱已被使用");
        }
        AppUser user = new AppUser();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(normalizeDisplayName(displayName, normalizedUsername));
        user.setEmail(normalizedEmail);
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
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
        }
        return issueLoginResponse(user);
    }

    public Map<String, Object> issueLoginResponseForUser(AppUser user) {
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "账号不可用");
        }
        return issueLoginResponse(user);
    }

    public AppUser findByEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getEmail, normalized).last("LIMIT 1"));
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
        info.put("email", user.getEmail());
        info.put("role", user.getRole());
        return info;
    }

    private AppUser findByUsername(String username) {
        return getOne(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username).last("LIMIT 1"));
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "请输入用户名");
        }
        String value = username.trim().toLowerCase();
        if (!value.matches("[a-z0-9_.-]{3,32}")) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "用户名需为 3-32 位字母、数字、下划线、点或短横线");
        }
        return value;
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        String value = email.trim().toLowerCase();
        if (value.length() > 254 || !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "请输入有效的邮箱地址");
        }
        return value;
    }

    private String normalizeDisplayName(String displayName, String fallback) {
        if (!StringUtils.hasText(displayName)) {
            return fallback;
        }
        String value = displayName.trim();
        if (value.length() > 100 || value.chars().anyMatch(Character::isISOControl)
                || value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "显示名称包含不支持的字符或超过长度限制");
        }
        return value;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 72) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "密码长度需为 6-72 位");
        }
    }

    /** 更新或设置邮箱；传 null 清除邮箱。 */
    public void updateEmail(Integer userId, String email) {
        String normalized = normalizeEmail(email);
        AppUser user = getById(userId);
        if (user == null) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "用户不存在");
        }
        if (normalized != null) {
            AppUser byEmail = findByEmail(normalized);
            if (byEmail != null && !byEmail.getUserId().equals(userId)) {
                throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "邮箱已被其他账号使用");
            }
        }
        user.setEmail(normalized);
        user.setUpdateTime(LocalDateTime.now());
        updateById(user);
    }

    /** 修改用户名；格式 3-32 位小写字母/数字/下划线/点/短横线，全局唯一。 */
    public void updateUsername(Integer userId, String username) {
        String normalized = normalizeUsername(username);
        AppUser user = getById(userId);
        if (user == null) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "用户不存在");
        }
        AppUser byUsername = findByUsername(normalized);
        if (byUsername != null && !byUsername.getUserId().equals(userId)) {
            throw new AuthException(AuthErrorCode.VALIDATION_FAILED, "用户名已被其他账号使用");
        }
        user.setUsername(normalized);
        user.setUpdateTime(LocalDateTime.now());
        updateById(user);
    }
}

