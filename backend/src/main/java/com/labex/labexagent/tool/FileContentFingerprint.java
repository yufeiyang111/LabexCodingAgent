package com.labex.labexagent.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 统一计算可编辑文件内容的完整 SHA-256 指纹。 */
public final class FileContentFingerprint {
    private static final int HASH_BUFFER_BYTES = 8192;

    private FileContentFingerprint() {
    }

    public static String sha256(String content) {
        return hex(digest().digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
    }

    /** 以流式摘要计算实际文件内容，避免为后置验证把大文件整体读入内存。 */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format(java.util.Locale.ROOT, "%02x", value));
        }
        return output.toString();
    }
}
