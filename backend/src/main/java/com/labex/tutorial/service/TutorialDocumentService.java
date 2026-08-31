package com.labex.tutorial.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.labex.entity.TutorialDocument;
import com.labex.mapper.TutorialDocumentMapper;
import com.labex.tutorial.dto.TutorialDetail;
import com.labex.tutorial.dto.TutorialSummary;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TutorialDocumentService extends ServiceImpl<TutorialDocumentMapper, TutorialDocument> {

    public List<TutorialSummary> listPublished() {
        return this.lambdaQuery()
                .eq(TutorialDocument::getStatus, 1)
                .orderByAsc(TutorialDocument::getSortOrder)
                .orderByAsc(TutorialDocument::getDocumentId)
                .list()
                .stream()
                .map(this::toSummary)
                .toList();
    }

    public TutorialDetail getPublished(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        TutorialDocument document = this.lambdaQuery()
                .eq(TutorialDocument::getSlug, slug.trim())
                .eq(TutorialDocument::getStatus, 1)
                .one();
        return document == null ? null : toDetail(document);
    }

    private TutorialSummary toSummary(TutorialDocument document) {
        return new TutorialSummary(document.getDocumentId(), document.getSlug(), document.getTitle(),
                document.getSummary(), document.getCategory(), document.getSortOrder(), document.getUpdateTime());
    }

    private TutorialDetail toDetail(TutorialDocument document) {
        return new TutorialDetail(document.getDocumentId(), document.getSlug(), document.getTitle(),
                document.getSummary(), document.getCategory(), document.getContentMarkdown(),
                document.getSortOrder(), document.getUpdateTime(), document.getPublishedAt());
    }
}
