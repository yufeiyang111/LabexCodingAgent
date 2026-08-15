package com.labex.labexagent.runtime;

import com.labex.labexagent.run.PreviewEvidence;
import com.labex.labexagent.run.RunCompletionEvidence;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunFinalizerPreviewClaimTest {

    @Test
    void rejectsAModelFinalThatClaimsAnUnavailablePreviewIsReachable() {
        RunCompletionEvidence evidence = evidence(new PreviewEvidence(PreviewEvidence.Status.FAILED, "", "process_exited",
                ".labex-agent/artifacts/preview/failed.log"));

        AgentRunFinalizer.CompletionAssessment assessment = AgentRunFinalizer.assessFinalText(
                evidence, "应用已启动，请访问 http://localhost:5000");

        assertFalse(assessment.allowed());
        assertTrue(assessment.guidance().contains("preview"));
    }

    @Test
    void acceptsTheCapturedMarkdownReadyUrlInAModelFinal() {
        RunCompletionEvidence evidence = evidence(new PreviewEvidence(PreviewEvidence.Status.READY,
                "http://localhost:5000/", "", ""));

        AgentRunFinalizer.CompletionAssessment assessment = AgentRunFinalizer.assessFinalText(
                evidence, "项目已成功运行并验证通过，网站可访问：[http://localhost:5000/](http://localhost:5000/)");

        assertTrue(assessment.allowed());
    }

    @Test
    void acceptsTheExactReadyUrlInAModelFinal() {
        RunCompletionEvidence evidence = evidence(new PreviewEvidence(PreviewEvidence.Status.READY,
                "http://localhost:5000/", "", ""));

        AgentRunFinalizer.CompletionAssessment assessment = AgentRunFinalizer.assessFinalText(
                evidence, "请访问 http://localhost:5000/");

        assertTrue(assessment.allowed());
    }

    private RunCompletionEvidence evidence(PreviewEvidence preview) {
        return new RunCompletionEvidence(9L, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                true, preview, LocalDateTime.now());
    }
}