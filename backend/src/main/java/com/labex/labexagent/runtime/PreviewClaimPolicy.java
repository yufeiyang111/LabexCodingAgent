package com.labex.labexagent.runtime;

import com.labex.labexagent.run.PreviewEvidence;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将模型的预览可访问性声明限制在当前 task 的 durable preview 事实上。 */
public final class PreviewClaimPolicy {
    /** 先提取 Markdown destination，避免裸 URL 匹配跨越 `](...)` 分隔符。 */
    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "\\[[^\\]]*\\]\\((https?://[^\\s<>\\[\\]()]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_HTTP_URL = Pattern.compile(
            "https?://[^\\s<>\\[\\]()]+", Pattern.CASE_INSENSITIVE);
    private static final String TERMINAL_SENTENCE_PUNCTUATION = ".,;:!?\u3002\uff0c\uff1b\uff1a\uff01\uff1f";

    private PreviewClaimPolicy() {
    }

    public static Assessment assess(String finalText, PreviewEvidence evidence) {
        String text = finalText == null ? "" : finalText;
        PreviewEvidence fact = evidence == null
                ? new PreviewEvidence(PreviewEvidence.Status.NOT_REQUESTED, "", "", "")
                : evidence;
        List<String> allUrls = urls(text);
        boolean containsClaimPhrase = containsPreviewSuccessClaim(text);

        // 仅将本地服务地址或伴随明确服务启动声称的 URL 识别为预览声明候选，
        // 绝不能将调研报告中引用的 GitHub 仓库、API 官方文档等外部 HTTP/HTTPS 链接误判为项目预览服务。
        List<String> previewUrls = allUrls.stream()
                .filter(url -> isLocalOrPreviewCandidate(url, fact.publicUrl(), containsClaimPhrase))
                .toList();

        boolean claimsPreviewSuccess = !previewUrls.isEmpty() || containsClaimPhrase;
        if (!claimsPreviewSuccess) {
            return Assessment.permitted();
        }
        if (!fact.ready()) {
            return Assessment.rejected("preview_not_ready",
                    "preview is not ready; do not claim that the service is running or provide a preview URL");
        }
        for (String url : previewUrls) {
            if (!fact.publicUrl().equals(url)) {
                return Assessment.rejected("preview_url_mismatch",
                        "preview URL is not the durable ready URL; report only " + fact.publicUrl());
            }
        }
        return Assessment.permitted();
    }

    private static boolean isLocalOrPreviewCandidate(String url, String readyPublicUrl, boolean containsClaimPhrase) {
        if (url == null || url.isBlank()) return false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.equals("localhost") || normalizedHost.equals("127.0.0.1")
                    || normalizedHost.equals("0.0.0.0") || normalizedHost.equals("[::1]")) {
                return true;
            }
            if (readyPublicUrl != null && !readyPublicUrl.isBlank()) {
                java.net.URI readyUri = java.net.URI.create(readyPublicUrl);
                if (normalizedHost.equalsIgnoreCase(readyUri.getHost())) {
                    return true;
                }
            }
            return containsClaimPhrase;
        } catch (Exception ignored) {
            return containsClaimPhrase;
        }
    }

    private static List<String> urls(String text) {
        Set<String> values = new LinkedHashSet<>();
        StringBuilder remaining = new StringBuilder(text == null ? "" : text);
        Matcher markdown = MARKDOWN_LINK.matcher(remaining);
        while (markdown.find()) {
            addUrl(values, markdown.group(1));
            for (int index = markdown.start(); index < markdown.end(); index++) {
                remaining.setCharAt(index, ' ');
            }
        }
        maskInlineCode(remaining);
        Matcher bare = BARE_HTTP_URL.matcher(remaining);
        while (bare.find()) {
            addUrl(values, bare.group());
        }
        return List.copyOf(values);
    }

    /** 在扫描裸 URL 前屏蔽 Markdown 行内/围栏代码，避免反引号进入 URL 候选。 */
    private static void maskInlineCode(StringBuilder text) {
        int start = 0;
        while (start < text.length()) {
            if (text.charAt(start) != '`') {
                start++;
                continue;
            }
            int delimiterLength = 1;
            while (start + delimiterLength < text.length() && text.charAt(start + delimiterLength) == '`') {
                delimiterLength++;
            }
            int end = findClosingCodeDelimiter(text, start + delimiterLength, delimiterLength);
            if (end < 0) {
                start += delimiterLength;
                continue;
            }
            for (int index = start; index < end + delimiterLength; index++) {
                text.setCharAt(index, ' ');
            }
            start = end + delimiterLength;
        }
    }

    private static int findClosingCodeDelimiter(StringBuilder text, int from, int delimiterLength) {
        for (int index = from; index <= text.length() - delimiterLength; index++) {
            boolean matches = true;
            for (int offset = 0; offset < delimiterLength; offset++) {
                if (text.charAt(index + offset) != '`') {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return index;
            }
        }
        return -1;
    }
    private static void addUrl(Set<String> values, String value) {
        String normalized = withoutSentencePunctuation(value);
        if (!normalized.isBlank()) {
            values.add(normalized);
        }
    }

    private static String withoutSentencePunctuation(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int end = value.length();
        while (end > 0 && TERMINAL_SENTENCE_PUNCTUATION.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean containsPreviewSuccessClaim(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("服务已启动")
                || normalized.contains("应用已启动")
                || normalized.contains("网站已启动")
                || normalized.contains("已启动并可访问")
                || normalized.contains("应用已重新就绪")
                || normalized.contains("服务正在运行")
                || normalized.contains("server is running")
                || normalized.contains("service is running")
                || normalized.contains("preview is ready")
                || normalized.contains("accessible at");
    }

    public record Assessment(boolean allowed, String code, String guidance) {
        public static Assessment permitted() {
            return new Assessment(true, "", "");
        }

        public static Assessment rejected(String code, String guidance) {
            String safeCode = code == null || code.isBlank() ? "preview_claim_unverified" : code;
            String safeGuidance = guidance == null ? "preview claim is not verified" : guidance;
            return new Assessment(false, safeCode, safeGuidance);
        }
    }
}
