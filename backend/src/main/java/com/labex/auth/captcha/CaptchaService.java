package com.labex.auth.captcha;

import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import com.labex.auth.redis.AuthRedisStore;
import com.labex.auth.redis.AuthRateLimitService;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import com.labex.auth.AuthDigest;

/** 生成和一次性校验图形验证码。 */
@Service
public class CaptchaService {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final SecureRandom random = new SecureRandom();
    private final AuthRedisStore store;
    private final AuthRateLimitService rateLimitService;
    private final AuthSecurityProperties properties;

    public CaptchaService(AuthRedisStore store, AuthRateLimitService rateLimitService,
                          AuthSecurityProperties properties) {
        this.store = store;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    public Map<String, Object> create(String scene, String source) {
        String normalizedScene = normalizeScene(scene);
        rateLimitService.checkCaptcha(normalizeSource(source));
        String captchaId = randomToken(18);
        String code = randomCode();
        String value = normalizedScene + "|" + digest(captchaId + ":" + code);
        store.put("labex:auth:captcha:" + captchaId, value,
                Duration.ofSeconds(Math.max(1, properties.getCaptchaTtlSeconds())));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("captchaId", captchaId);
        payload.put("image", "data:image/png;base64," + render(code));
        payload.put("expiresInSeconds", properties.getCaptchaTtlSeconds());
        return payload;
    }

    public void verify(String scene, String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new AuthException(AuthErrorCode.CAPTCHA_INVALID, "图形验证码无效或已过期");
        }
        String stored = store.consume("labex:auth:captcha:" + captchaId.trim());
        if (stored == null) {
            throw new AuthException(AuthErrorCode.CAPTCHA_INVALID, "图形验证码无效或已过期");
        }
        String[] parts = stored.split("\\|", 2);
        String expectedScene = parts.length == 2 ? parts[0] : "";
        String expectedDigest = parts.length == 2 ? parts[1] : "";
        String actualScene = normalizeScene(scene);
        String actualDigest = digest(captchaId.trim() + ":" + captchaCode.trim().toUpperCase(Locale.ROOT));
        if (!expectedScene.equals(actualScene) || !MessageDigest.isEqual(
                expectedDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actualDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new AuthException(AuthErrorCode.CAPTCHA_INVALID, "图形验证码无效或已过期");
        }
    }

    private String randomCode() {
        int length = Math.max(4, Math.min(properties.getCaptchaLength(), 8));
        StringBuilder code = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String render(String code) {
        int width = Math.max(96, properties.getCaptchaWidth());
        int height = Math.max(36, properties.getCaptchaHeight());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(247, 244, 236));
            graphics.fillRect(0, 0, width, height);
            for (int index = 0; index < Math.max(0, properties.getCaptchaNoiseLines()); index++) {
                graphics.setColor(new Color(120 + random.nextInt(70), 120 + random.nextInt(70), 110 + random.nextInt(70)));
                graphics.drawLine(random.nextInt(width), random.nextInt(height),
                        random.nextInt(width), random.nextInt(height));
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(18, properties.getCaptchaFontSize())));
            int step = width / (code.length() + 1);
            for (int index = 0; index < code.length(); index++) {
                graphics.setColor(new Color(65 + random.nextInt(55), 75 + random.nextInt(45), 65 + random.nextInt(45)));
                graphics.drawString(String.valueOf(code.charAt(index)),
                        step * (index + 1) - 7, height - 12 + random.nextInt(5));
            }
            graphics.setColor(new Color(130, 118, 101));
            for (int index = 0; index < Math.max(0, properties.getCaptchaNoiseDots()); index++) {
                graphics.fillRect(random.nextInt(width), random.nextInt(height), 1, 1);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception failure) {
            throw new AuthException(AuthErrorCode.REDIS_UNAVAILABLE, "验证码服务暂时不可用，请稍后重试");
        } finally {
            graphics.dispose();
        }
    }

    private String normalizeScene(String scene) {
        return "register".equalsIgnoreCase(scene) ? "register" : "login";
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        return source.replaceAll("[^a-zA-Z0-9:._-]", "_");
    }

    private String digest(String value) {
        return AuthDigest.sha256Hex(value);
    }
}
