package com.labex.rag.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.rag.config.RagConfig;
import org.junit.jupiter.api.Test;

class ImageUnderstandingServicePolicyTest {

    @Test
    void rejectsPrivateImageUrlsBeforeAttemptingTheDownload() {
        RagConfig config = new RagConfig();
        config.setImageUnderstandingEnabled(true);
        config.setMiniMaxApiKey("test-key");
        ImageUnderstandingService service = new ImageUnderstandingService(config);

        ImageUnderstandingService.ImageAnalysisResult result = service.analyzeImage(
                "describe the image", "http://127.0.0.1:1/image.png", "private-image");

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().toLowerCase().contains("blocked"));
    }
}
