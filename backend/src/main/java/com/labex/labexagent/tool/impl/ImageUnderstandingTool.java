package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.labexagent.multimodal.ConfiguredImageUnderstandingService;
import org.springframework.stereotype.Component;

@Component
public class ImageUnderstandingTool implements AgentTool {

    private final ConfiguredImageUnderstandingService imageUnderstandingService;

    public ImageUnderstandingTool(ConfiguredImageUnderstandingService imageUnderstandingService) {
        this.imageUnderstandingService = imageUnderstandingService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("understand_image")
                .description("Analyze image content from a data URL, an HTTP(S) image URL, or a local image path. Use it for screenshots, OCR text, charts, code, and error messages.")
                .stringProperty("prompt", "Question or analysis request for the image", true)
                .stringProperty("image_url", "Image source: a data URL, HTTP(S) URL, or local image path", true)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String prompt = ToolSupport.stringArg(args, "prompt", "");
        String imageUrl = ToolSupport.stringArg(args, "image_url", "");
        if (prompt.isBlank()) {
            return ToolResult.failed("prompt is required");
        }
        if (imageUrl.isBlank()) {
            return ToolResult.failed("image_url is required");
        }

        try {
            ConfiguredImageUnderstandingService.ImageAnalysisResult result = imageUnderstandingService.analyzeImage(
                    context, prompt, safeImageSource(context, imageUrl), "tool-image");
            return result.success() ? ToolResult.ok(result.content()) : ToolResult.failed(result.content());
        } catch (IllegalArgumentException e) {
            return ToolResult.failed("unsafe image source: " + e.getMessage());
        }
    }

    private String safeImageSource(AgentContext context, String imageSource) {
        String source = imageSource == null ? "" : imageSource.trim();
        if (source.startsWith("@")) {
            source = source.substring(1).trim();
        }
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("data:") || lower.startsWith("http://") || lower.startsWith("https://")) {
            return source;
        }
        return ToolSupport.resolve(context, source).toString();
    }
}
