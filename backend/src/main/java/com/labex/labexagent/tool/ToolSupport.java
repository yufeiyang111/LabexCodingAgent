package com.labex.labexagent.tool;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/*
 * Exception performing whole class analysis ignored.
 */
public final class ToolSupport {
    private ToolSupport() {
    }

    public static String stringArg(JsonObject args, String name, String defaultValue) {
        return args != null && args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsString() : defaultValue;
    }

    public static String stringArgMulti(JsonObject args, String defaultValue, String ... names) {
        if (args == null) {
            return defaultValue;
        }
        for (String name : names) {
            String v;
            if (!args.has(name) || args.get(name).isJsonNull() || (v = args.get(name).getAsString()) == null || v.isEmpty()) continue;
            return v;
        }
        return defaultValue;
    }

    public static int intArg(JsonObject args, String name, int defaultValue) {
        try {
            return args != null && args.has(name) && !args.get(name).isJsonNull() ? args.get(name).getAsInt() : defaultValue;
        }
        catch (Exception e) {
            return defaultValue;
        }
    }

    public static Path resolve(AgentContext context, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path root = context.getWorkspaceRoot();
        String cleaned = ToolSupport.normalizeRelativePath((String)relativePath);
        if (".".equals(cleaned) || "/".equals(cleaned)) {
            return root;
        }
        Path resolved = root.resolve(cleaned).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe file path");
        }
        return resolved;
    }

    public static String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String cleaned = relativePath.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        int workspaceMarker = cleaned.lastIndexOf("/workspace/");
        if (workspaceMarker >= 0) {
            cleaned = cleaned.substring(workspaceMarker + "/workspace/".length());
        }
        if ("workspace".equals(cleaned)) {
            return "";
        }
        while (cleaned.startsWith("workspace/")) {
            cleaned = cleaned.substring("workspace/".length());
        }
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        return cleaned;
    }

    public static boolean isLikelyBinary(Path file) throws Exception {
        if (!Files.isRegularFile(file, new LinkOption[0])) {
            return false;
        }
        byte[] bytes = Files.readAllBytes(file);
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; ++i) {
            if (bytes[i] != 0) continue;
            return true;
        }
        return false;
    }

    public static String limit(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "\n...\u8f93\u51fa\u5df2\u622a\u65ad...";
    }
}

