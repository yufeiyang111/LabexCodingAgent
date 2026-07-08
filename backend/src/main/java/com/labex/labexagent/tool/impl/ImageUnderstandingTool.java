package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.rag.service.ImageUnderstandingService;
import org.springframework.stereotype.Component;

@Component
public class ImageUnderstandingTool implements AgentTool {

    private final ImageUnderstandingService imageUnderstandingService;

    public ImageUnderstandingTool(ImageUnderstandingService imageUnderstandingService) {
        this.imageUnderstandingService = imageUnderstandingService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("understand_image")
                .description("读取并分析图片内容，支持 data URL、HTTP/HTTPS 图片 URL 或本地图片路径。适合识别截图、OCR文字、图表、代码和报错。")
                .stringProperty("prompt", "对图片的提问或分析要求", true)
                .stringProperty("image_url", "图片来源，支持 data URL、HTTP/HTTPS URL 或本地图片路径", true)
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

        ImageUnderstandingService.ImageAnalysisResult result =
                imageUnderstandingService.analyzeImage(prompt, imageUrl, "tool-image");
        return result.isSuccess() ? ToolResult.ok(result.getContent()) : ToolResult.failed(result.getContent());
    }
}
