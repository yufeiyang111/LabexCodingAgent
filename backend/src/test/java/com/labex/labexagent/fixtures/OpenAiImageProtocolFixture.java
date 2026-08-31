package com.labex.labexagent.fixtures;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** 生成真实 PNG 并按 OpenAI-compatible image_url 协议封装，避免用伪造的字符串替代多模态输入。 */
public final class OpenAiImageProtocolFixture {
    private static final String LARGE_PNG_DATA_URL = createLargePngDataUrl();

    private OpenAiImageProtocolFixture() {
    }

    public static Map<String, Object> imagePart() {
        return Map.of(
                "type", "image_url",
                "image_url", Map.of("url", LARGE_PNG_DATA_URL));
    }

    public static Map<String, Object> userMessage(String text) {
        return Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "text", "text", text == null ? "" : text),
                        imagePart()));
    }

    /** 注水后的多图单条 user 消息，与 hydrateProviderMessage 的输出形态同构。 */
    public static List<Map<String, Object>> userMessagesOf(int imageCount) {
        List<Object> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", "inspect this screenshot"));
        for (int i = 0; i < Math.max(1, imageCount); i++) {
            content.add(imagePart());
        }
        return List.of(Map.of("role", "user", "content", content));
    }

    public static int dataUrlChars() {
        return LARGE_PNG_DATA_URL.length();
    }

    private static int noise(int x, int y) {
        int value = x * 0x1F123BB5 ^ y * 0x5F356495 ^ 0x68BC21EB;
        value ^= value >>> 16;
        value *= 0x7FEB352D;
        value ^= value >>> 15;
        value *= 0x846CA68B;
        return value ^ value >>> 16;
    }

    private static String createLargePngDataUrl() {
        try {
            BufferedImage image = new BufferedImage(350, 350, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int value = noise(x, y);
                    image.setRGB(x, y, value & 0x00FFFFFF);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG encoder is unavailable");
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to construct OpenAI-compatible image fixture", failure);
        }
    }
}
