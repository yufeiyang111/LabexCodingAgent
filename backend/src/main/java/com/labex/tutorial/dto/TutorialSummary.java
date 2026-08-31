package com.labex.tutorial.dto;

import java.time.LocalDateTime;

public record TutorialSummary(
        Long documentId,
        String slug,
        String title,
        String summary,
        String category,
        Integer sortOrder,
        LocalDateTime updatedAt) {
}
