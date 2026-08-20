package com.labex.auth.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.auth.AuthErrorCode;
import com.labex.auth.AuthException;
import com.labex.auth.config.AuthSecurityProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** OAuth Provider 的最小 HTTP 适配器，统一超时和错误处理。 */
@Component
public class OAuthHttpClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthSecurityProperties properties;

    public OAuthHttpClient(ObjectMapper objectMapper, AuthSecurityProperties properties) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getOauthHttpTimeoutSeconds())))
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public JsonNode postForm(String url, Map<String, String> values) {
        String body = values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getOauthHttpTimeoutSeconds())))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    public JsonNode getJson(String url, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, properties.getOauthHttpTimeoutSeconds())))
                .header("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return send(builder.GET().build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "第三方授权失败，请重试");
            }
            return objectMapper.readTree(response.body());
        } catch (AuthException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "第三方授权服务暂时不可用，请重试");
        } catch (IOException failure) {
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "第三方授权服务暂时不可用，请重试");
        } catch (RuntimeException failure) {
            throw new AuthException(AuthErrorCode.OAUTH_CODE_INVALID, "第三方授权响应无效，请重试");
        }
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
