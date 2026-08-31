package com.labex.tutorial.controller;

import com.labex.common.Result;
import com.labex.tutorial.dto.TutorialDetail;
import com.labex.tutorial.dto.TutorialSummary;
import com.labex.tutorial.service.TutorialDocumentService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tutorials")
public class TutorialDocumentController {
    private final TutorialDocumentService tutorialDocumentService;

    public TutorialDocumentController(TutorialDocumentService tutorialDocumentService) {
        this.tutorialDocumentService = tutorialDocumentService;
    }

    @GetMapping
    public Result<List<TutorialSummary>> list() {
        return Result.success(tutorialDocumentService.listPublished());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<Result<TutorialDetail>> detail(@PathVariable String slug) {
        TutorialDetail detail = tutorialDocumentService.getPublished(slug);
        if (detail == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error(-1, "教程不存在或尚未发布"));
        }
        return ResponseEntity.ok(Result.success(detail));
    }
}
