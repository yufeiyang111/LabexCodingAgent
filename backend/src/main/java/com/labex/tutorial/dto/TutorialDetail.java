package com.labex.tutorial.dto;

import java.time.LocalDateTime;

public record TutorialDetail(
        Long documentId,
        String slug,
        String title,
        String summary,
        String category,
        String contentMarkdown,
        Integer sortOrder,
        LocalDateTime updatedAt,
        LocalDateTime publishedAt) {
}
