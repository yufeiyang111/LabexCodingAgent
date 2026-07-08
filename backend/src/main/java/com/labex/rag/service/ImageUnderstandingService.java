package com.labex.rag.service;

import com.labex.rag.config.RagConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class ImageUnderstandingService {

    private static final int MAX_IMAGES = 6;
    private static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;

    private final RagConfig ragConfig;
    private final RestTemplate restTemplate;

    public ImageUnderstandingService(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(120000);
        this.restTemplate = new RestTemplate(factory);
    }

    public List<Map<String, Object>> analyzeAttachments(String question, List<Map<String, Object>> attachments) {
        List<Map<String, Object>> sources = new ArrayList<>();
        if (attachments == null || attachments.isEmpty()) {
            return sources;
        }

        String prompt = buildPrompt(question);
        for (int i = 0; i < attachments.size() && i < MAX_IMAGES; i++) {
            Map<String, Object> attachment = attachments.get(i);
            String name = Objects.toString(attachment.get("name"), "image-" + (i + 1));
            String imageUrl = Objects.toString(attachment.get("url"), "");
            ImageAnalysisResult result = analyzeImage(prompt, imageUrl, name);

            Map<String, Object> source = new HashMap<>();
            source.put("type", "image");
            source.put("title", "图片理解：" + name);
            source.put("text", result.getContent());
            source.put("snippet", truncate(result.getContent(), 260));
            source.put("tool", "understand_image");
            source.put("success", result.isSuccess());
            source.put("mimeType", attachment.get("mimeType"));
            source.put("name", name);
            source.put("index", i + 1);
            if (result.getError() != null && !result.getError().isBlank()) {
                source.put("error", result.getError());
            }
            sources.add(source);
        }
        return sources;
    }

    public ImageAnalysisResult analyzeImage(String prompt, String imageSource, String name) {
        if (!ragConfig.isImageUnderstandingEnabled()) {
            return ImageAnalysisResult.failed("图片理解功能未启用，请开启 rag.image-understanding-enabled。");
        }

        String apiKey = ragConfig.getMiniMaxApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ImageAnalysisResult.failed("MiniMax API key 未配置，无法调用 understand_image 工具。");
        }
        if (imageSource == null || imageSource.isBlank()) {
            return ImageAnalysisResult.failed("图片地址为空，无法读取图片内容。");
        }

        try {
            String processedImageUrl = processImageUrl(imageSource);
            Map<String, Object> body = new HashMap<>();
            body.put("prompt", prompt == null || prompt.isBlank() ? defaultPrompt() : prompt);
            body.put("image_url", processedImageUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            String url = resolveApiHost() + "/v1/coding_plan/vlm";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String content = extractContent(response.getBody());
                if (!content.isBlank()) {
                    return ImageAnalysisResult.ok(content);
                }
                String error = extractError(response.getBody());
                return ImageAnalysisResult.failed(error.isBlank() ? "MiniMax VLM 未返回图片理解内容。" : error);
            }
            return ImageAnalysisResult.failed("MiniMax VLM 请求失败，HTTP 状态：" + response.getStatusCode().value());
        } catch (Exception e) {
            log.warn("understand_image failed for {}: {}", name, e.getMessage());
            return ImageAnalysisResult.failed("图片理解失败：" + e.getMessage());
        }
    }

    private String buildPrompt(String question) {
        String userQuestion = question == null || question.isBlank() ? "用户只上传了图片，请概括图片内容并提取关键信息。" : question;
        return """
                请分析这张图片，并输出可直接用于回答用户问题的结构化结果：
                1. 图片主要内容；
                2. 图片中可见文字或代码，尽量 OCR 提取；
                3. 如果是界面截图、报错、图表、流程图或文档，请指出关键元素和含义；
                4. 与用户问题最相关的信息；
                5. 如果图片信息不足，请明确说明不确定点。

                用户问题：%s
                """.formatted(userQuestion);
    }

    private String defaultPrompt() {
        return "请描述图片内容，提取可见文字，并说明与用户问题相关的关键信息。";
    }

    private String processImageUrl(String imageSource) throws Exception {
        String source = imageSource.startsWith("@") ? imageSource.substring(1) : imageSource;
        if (source.startsWith("data:")) {
            validateDataUrl(source);
            return source;
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            ResponseEntity<byte[]> response = restTemplate.getForEntity(URI.create(source), byte[].class);
            byte[] data = response.getBody();
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("无法下载图片或图片为空。");
            }
            if (data.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("图片超过 20MB。");
            }
            String contentType = response.getHeaders().getContentType() == null
                    ? inferMimeType(source, null)
                    : response.getHeaders().getContentType().toString();
            return toDataUrl(contentType, data);
        }

        Path path = Path.of(source).normalize();
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("本地图片文件不存在：" + source);
        }
        long size = Files.size(path);
        if (size > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片超过 20MB。");
        }
        byte[] data = Files.readAllBytes(path);
        String contentType = inferMimeType(source, Files.probeContentType(path));
        return toDataUrl(contentType, data);
    }

    private void validateDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma <= 0 || !dataUrl.substring(0, comma).toLowerCase(Locale.ROOT).contains(";base64")) {
            throw new IllegalArgumentException("图片 data URL 格式不正确。");
        }
        String header = dataUrl.substring(0, comma).toLowerCase(Locale.ROOT);
        if (!isSupportedMime(header.replace("data:", "").replace(";base64", ""))) {
            throw new IllegalArgumentException("仅支持 JPEG、PNG、GIF、WebP 图片。");
        }
        long approxBytes = (long) ((dataUrl.length() - comma - 1) * 0.75);
        if (approxBytes > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片超过 20MB。");
        }
    }

    private String toDataUrl(String contentType, byte[] data) {
        String mimeType = normalizeMimeType(contentType);
        if (!isSupportedMime(mimeType)) {
            throw new IllegalArgumentException("仅支持 JPEG、PNG、GIF、WebP 图片。");
        }
        return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    private String inferMimeType(String source, String detected) {
        String contentType = detected == null ? "" : detected.toLowerCase(Locale.ROOT);
        if (isSupportedMime(contentType)) {
            return contentType;
        }
        String lower = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private String normalizeMimeType(String contentType) {
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("png")) {
            return "image/png";
        }
        if (lower.contains("webp")) {
            return "image/webp";
        }
        if (lower.contains("gif")) {
            return "image/gif";
        }
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return "image/jpeg";
        }
        return lower.isBlank() ? "image/jpeg" : lower;
    }

    private boolean isSupportedMime(String mimeType) {
        return Set.of("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp").contains(mimeType);
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> body) {
        Object content = body.get("content");
        if (content != null) {
            return content.toString();
        }
        Object data = body.get("data");
        if (data instanceof Map<?, ?> dataMap && dataMap.get("content") != null) {
            return dataMap.get("content").toString();
        }
        Object choices = body.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> choice) {
            Object message = choice.get("message");
            if (message instanceof Map<?, ?> messageMap && messageMap.get("content") != null) {
                return messageMap.get("content").toString();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private String extractError(Map<String, Object> body) {
        Object baseResp = body.get("base_resp");
        if (baseResp instanceof Map<?, ?> baseMap && baseMap.get("status_msg") != null) {
            return baseMap.get("status_msg").toString();
        }
        Object error = body.get("error");
        return error == null ? "" : error.toString();
    }

    private String resolveApiHost() {
        String host = ragConfig.getMiniMaxApiHost();
        if (host != null && !host.isBlank()) {
            return host.replaceAll("/$", "");
        }
        String baseUrl = ragConfig.getMiniMaxBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.minimaxi.com";
        }
        return baseUrl.replaceAll("/v1/?$", "").replaceAll("/$", "");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }

    @Getter
    public static class ImageAnalysisResult {
        private final boolean success;
        private final String content;
        private final String error;

        private ImageAnalysisResult(boolean success, String content, String error) {
            this.success = success;
            this.content = content;
            this.error = error;
        }

        public static ImageAnalysisResult ok(String content) {
            return new ImageAnalysisResult(true, content, null);
        }

        public static ImageAnalysisResult failed(String error) {
            return new ImageAnalysisResult(false, error, error);
        }
    }
}
