package com.labex.labexagent.runtime;

import com.labex.labexagent.run.PreviewEvidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewClaimPolicyTest {

    @Test
    void rejectsAnUnverifiedPreviewUrlAfterTheManagedPreviewExited() {
        PreviewEvidence failed = new PreviewEvidence(PreviewEvidence.Status.FAILED, "", "process_exited",
                ".labex-agent/artifacts/preview/run.log");

        PreviewClaimPolicy.Assessment assessment = PreviewClaimPolicy.assess(
                "服务已启动，请访问 http://localhost:5000", failed);

        assertFalse(assessment.allowed());
        assertTrue(assessment.guidance().contains("preview"));
    }

    @Test
    void allowsOnlyTheExactDurablyReadyPreviewUrl() {
        PreviewEvidence ready = new PreviewEvidence(PreviewEvidence.Status.READY, "http://localhost:5000/", "", "");

        assertTrue(PreviewClaimPolicy.assess("请访问 http://localhost:5000/", ready).allowed());
        assertFalse(PreviewClaimPolicy.assess("请访问 http://127.0.0.1:5000/", ready).allowed());
    }

    @Test
    void acceptsTheExactReadyUrlWhenTheChineseSentenceEndsWithPunctuation() {
        PreviewEvidence ready = new PreviewEvidence(PreviewEvidence.Status.READY, "http://localhost:5000/", "", "");

        assertTrue(PreviewClaimPolicy.assess("请访问 http://localhost:5000/。", ready).allowed());
    }

    @Test
    void acceptsTheDurableReadyUrlWhenTheModelUsesAnInlineMarkdownLink() {
        PreviewEvidence ready = new PreviewEvidence(PreviewEvidence.Status.READY, "http://localhost:5000/", "", "");

        PreviewClaimPolicy.Assessment assessment = PreviewClaimPolicy.assess(
                "项目已成功运行并验证通过，网站可访问：[http://localhost:5000/](http://localhost:5000/)", ready);

        assertTrue(assessment.allowed());
    }

    @Test
    void ignoresTheDurableUrlInsideInlineCodeWhenCheckingBareUrls() {
        PreviewEvidence ready = new PreviewEvidence(PreviewEvidence.Status.READY, "http://localhost:5000/", "", "");

        PreviewClaimPolicy.Assessment assessment = PreviewClaimPolicy.assess(
                "持久 URL `http://localhost:5000/`；访问地址：[http://localhost:5000/](http://localhost:5000/)", ready);

        assertTrue(assessment.allowed());
    }
    @Test
    void rejectsAMarkdownLinkWhoseDestinationIsNotTheDurableReadyUrl() {
        PreviewEvidence ready = new PreviewEvidence(PreviewEvidence.Status.READY, "http://localhost:5000/", "", "");

        PreviewClaimPolicy.Assessment assessment = PreviewClaimPolicy.assess(
                "请访问 [本地预览](http://127.0.0.1:5000/)", ready);

        assertFalse(assessment.allowed());
        assertEquals("preview_url_mismatch", assessment.code());
    }

    @Test
    void permitsAnHonestFailureReportWithoutAPreviewClaim() {
        PreviewEvidence failed = new PreviewEvidence(PreviewEvidence.Status.FAILED, "", "command_not_found",
                ".labex-agent/artifacts/preview/run.log");

        assertTrue(PreviewClaimPolicy.assess("预览启动失败：Worker 中不存在 python，请使用 python3。", failed).allowed());
    }
}
