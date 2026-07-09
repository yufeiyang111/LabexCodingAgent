package com.labex.labexagent.runtime;

import java.util.Locale;
import java.util.Map;

public final class VisibleLanguageResolver {
    private static final Map<String, Language> LANGUAGES = Map.of(
            "zh", new Language("zh", "Simplified Chinese"),
            "en", new Language("en", "English"),
            "ja", new Language("ja", "Japanese"),
            "ko", new Language("ko", "Korean")
    );

    private VisibleLanguageResolver() {
    }

    public static Language resolve(String userMessage, String previousLanguageCode) {
        String previous = normalizeCode(previousLanguageCode);
        if (isLikelyCodeOrCommand(userMessage)) {
            return language(previous == null ? "en" : previous);
        }
        ScriptCounts counts = countScripts(userMessage);
        if (!counts.hasNaturalLanguage()) {
            return language(previous == null ? "en" : previous);
        }
        String code = counts.dominantCode();
        return language(code == null ? (previous == null ? "en" : previous) : code);
    }

    public static Language language(String code) {
        return LANGUAGES.getOrDefault(normalizeCode(code), LANGUAGES.get("en"));
    }

    public static boolean isChinese(String code) {
        return "zh".equals(language(code).code());
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("zh") || normalized.equals("cn")) {
            return "zh";
        }
        if (normalized.startsWith("ja") || normalized.equals("jp")) {
            return "ja";
        }
        if (normalized.startsWith("ko") || normalized.equals("kr")) {
            return "ko";
        }
        if (normalized.startsWith("en")) {
            return "en";
        }
        return LANGUAGES.containsKey(normalized) ? normalized : null;
    }

    private static ScriptCounts countScripts(String text) {
        ScriptCounts counts = new ScriptCounts();
        if (text == null || text.isBlank()) {
            return counts;
        }
        for (int i = 0; i < text.length(); i++) {
            int codePoint = text.codePointAt(i);
            if (Character.isSupplementaryCodePoint(codePoint)) {
                i++;
            }
            if (Character.isDigit(codePoint) || Character.isWhitespace(codePoint)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) {
                counts.zh++;
            } else if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
                counts.ja++;
            } else if (script == Character.UnicodeScript.HANGUL) {
                counts.ko++;
            } else if (isLatinLetter(codePoint)) {
                counts.en++;
            }
        }
        return counts;
    }

    private static boolean isLatinLetter(int codePoint) {
        return Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static boolean isLikelyCodeOrCommand(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        ScriptCounts counts = countScripts(text);
        if (counts.zh > 0 || counts.ja > 0 || counts.ko > 0) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("`") || trimmed.contains("\\") || trimmed.contains("=")) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains(".") || lower.contains("/") || lower.contains("--")) {
            return true;
        }
        String[] tokens = lower.split("\\s+");
        if (tokens.length == 0 || tokens.length > 4) {
            return false;
        }
        int commandTokens = 0;
        for (String token : tokens) {
            if (token.matches("[a-z0-9_@./:-]+") && isCommandishToken(token)) {
                commandTokens++;
            }
        }
        return commandTokens == tokens.length;
    }

    private static boolean isCommandishToken(String token) {
        return token.equals("npm")
                || token.equals("pnpm")
                || token.equals("yarn")
                || token.equals("bun")
                || token.equals("mvn")
                || token.equals("gradle")
                || token.equals("java")
                || token.equals("node")
                || token.equals("git")
                || token.equals("run")
                || token.equals("test")
                || token.equals("build")
                || token.equals("dev")
                || token.equals("start")
                || token.equals("status")
                || token.equals("install")
                || token.equals("init")
                || token.matches("[a-z0-9_.-]+:[a-z0-9_.-]+");
    }

    public record Language(String code, String displayName) {
    }

    private static final class ScriptCounts {
        private int zh;
        private int en;
        private int ja;
        private int ko;

        private boolean hasNaturalLanguage() {
            return zh > 0 || en > 0 || ja > 0 || ko > 0;
        }

        private String dominantCode() {
            String code = "en";
            int max = en;
            int zhScore = zh * 4;
            int jaScore = ja * 4;
            int koScore = ko * 4;
            if (zhScore > max) {
                code = "zh";
                max = zhScore;
            }
            if (jaScore > max) {
                code = "ja";
                max = jaScore;
            }
            if (koScore > max) {
                code = "ko";
                max = koScore;
            }
            return max == 0 ? null : code;
        }
    }
}
