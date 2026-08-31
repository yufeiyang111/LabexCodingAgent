package com.labex.tutorial.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.labex.common.Result;
import com.labex.tutorial.dto.TutorialDetail;
import com.labex.tutorial.dto.TutorialSummary;
import com.labex.tutorial.service.TutorialDocumentService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TutorialDocumentControllerTest {
    @Mock
    private TutorialDocumentService tutorialDocumentService;

    @InjectMocks
    private TutorialDocumentController controller;

    @Test
    void listsPublishedTutorialSummaries() {
        when(tutorialDocumentService.listPublished()).thenReturn(List.of(
                new TutorialSummary(1L, "getting-started", "快速开始", "摘要", "快速开始", 10,
                        LocalDateTime.now())));

        Result<List<TutorialSummary>> result = controller.list();

        assertEquals(0, result.getCode());
        assertEquals("getting-started", result.getData().get(0).slug());
    }

    @Test
    void returnsNotFoundForUnpublishedOrMissingTutorial() {
        when(tutorialDocumentService.getPublished("draft")).thenReturn(null);

        var response = controller.detail("draft");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(-1, response.getBody().getCode());
    }

    @Test
    void returnsPublishedTutorialDetail() {
        when(tutorialDocumentService.getPublished("getting-started")).thenReturn(
                new TutorialDetail(1L, "getting-started", "快速开始", "摘要", "快速开始",
                        "# 快速开始", 10, LocalDateTime.now(), LocalDateTime.now()));

        var response = controller.detail("getting-started");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("# 快速开始", response.getBody().getData().contentMarkdown());
    }
}
