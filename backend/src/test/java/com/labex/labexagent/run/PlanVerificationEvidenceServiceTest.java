package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentPreviewRun;
import com.labex.entity.AgentRunPart;
import com.labex.mapper.AgentPreviewRunMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanVerificationEvidenceServiceTest {

    @Test
    void rejectsUnitTestEvidenceForABuildPlan() {
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentPreviewRunMapper previews = mock(AgentPreviewRunMapper.class);
        when(parts.currentEpochToolHistory(71L, 4L)).thenReturn(List.of(toolPart(
                "run_tests", "completed", "{\"strategy\":\"test\",\"target_path\":\"frontend\"}",
                "verification_command=python -m pytest", 4L)));

        PlanVerificationEvidenceService.Assessment assessment = new PlanVerificationEvidenceService(parts, previews)
                .assess(71L, 4L, new AgentRunPlanService.PlanItem(
                        "Build frontend", "Run the production build for target=frontend", "in_progress"));

        assertThat(assessment.required()).isTrue();
        assertThat(assessment.satisfied()).isFalse();
        assertThat(assessment.reasonCode()).isEqualTo("verification_kind_mismatch");
    }

    @Test
    void acceptsBuildEvidenceOnlyWhenItsDurableTargetMatchesThePlan() {
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentPreviewRunMapper previews = mock(AgentPreviewRunMapper.class);
        when(parts.currentEpochToolHistory(71L, 4L)).thenReturn(List.of(toolPart(
                "run_tests", "completed", "{\"strategy\":\"build\",\"target_path\":\"frontend\"}",
                "verification_command=npm run build", 4L)));
        PlanVerificationEvidenceService service = new PlanVerificationEvidenceService(parts, previews);

        PlanVerificationEvidenceService.Assessment wrongTarget = service.assess(71L, 4L,
                new AgentRunPlanService.PlanItem("Build frontend", "target=backend", "in_progress"));
        PlanVerificationEvidenceService.Assessment matchedTarget = service.assess(71L, 4L,
                new AgentRunPlanService.PlanItem("Build frontend", "target=frontend", "in_progress"));

        assertThat(wrongTarget.satisfied()).isFalse();
        assertThat(wrongTarget.reasonCode()).isEqualTo("verification_target_mismatch");
        assertThat(matchedTarget.satisfied()).isTrue();
        assertThat(matchedTarget.evidenceTool()).isEqualTo("run_tests");
    }

    @Test
    void homepageAvailabilityRequiresAReadyPreviewInTheCurrentEpoch() {
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentPreviewRunMapper previews = mock(AgentPreviewRunMapper.class);
        when(parts.currentEpochToolHistory(71L, 4L)).thenReturn(List.of(toolPart(
                "run_tests", "completed", "{\"strategy\":\"test\"}", "verification_command=pytest", 4L)));
        when(previews.selectList(any())).thenReturn(List.of());
        PlanVerificationEvidenceService service = new PlanVerificationEvidenceService(parts, previews);
        AgentRunPlanService.PlanItem plan = new AgentRunPlanService.PlanItem(
                "Verify homepage is accessible", "Use HTTP readiness before marking this done", "in_progress");

        PlanVerificationEvidenceService.Assessment missing = service.assess(71L, 4L, plan);
        assertThat(missing.satisfied()).isFalse();
        assertThat(missing.reasonCode()).isEqualTo("preview_not_ready");

        AgentPreviewRun ready = new AgentPreviewRun();
        ready.setTaskId(71L);
        ready.setStatus("ready");
        ready.setPublicUrl("http://127.0.0.1:3000/");
        when(parts.currentEpochToolHistory(71L, 4L)).thenReturn(List.of(toolPart(
                "start_preview", "completed", "{}", "preview_status=ready\npreview_url=http://127.0.0.1:3000/", 4L)));
        when(previews.selectList(any())).thenReturn(List.of(ready));

        PlanVerificationEvidenceService.Assessment matched = service.assess(71L, 4L, plan);
        assertThat(matched.satisfied()).isTrue();
        assertThat(matched.evidenceTool()).isEqualTo("start_preview");
    }

    @Test
    void acceptsManualFileVerificationOnlyForThePlanTarget() {
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentPreviewRunMapper previews = mock(AgentPreviewRunMapper.class);
        when(parts.currentEpochToolHistory(71L, 4L)).thenReturn(List.of(toolPart(
                "read_file", "completed", "{\"path\":\"frontend/settings.json\"}",
                "[read_file path=frontend/settings.json sha256=abc123]", 4L)));

        PlanVerificationEvidenceService.Assessment assessment = new PlanVerificationEvidenceService(parts, previews)
                .assess(71L, 4L, new AgentRunPlanService.PlanItem(
                        "Verify configuration", "Verify target=frontend/settings.json", "in_progress"));

        assertThat(assessment.satisfied()).isTrue();
        assertThat(assessment.evidenceTool()).isEqualTo("read_file");
    }

    @Test
    void ignoresVerificationWhenTheCurrentEpochHistoryIsEmpty() {
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentPreviewRunMapper previews = mock(AgentPreviewRunMapper.class);
        when(parts.currentEpochToolHistory(71L, 4L)).thenReturn(List.of());

        PlanVerificationEvidenceService.Assessment assessment = new PlanVerificationEvidenceService(parts, previews)
                .assess(71L, 4L, new AgentRunPlanService.PlanItem("Build frontend", "Run build", "in_progress"));

        assertThat(assessment.satisfied()).isFalse();
        assertThat(assessment.reasonCode()).isEqualTo("verification_missing");
    }

    private AgentRunPart toolPart(String toolName, String status, String input, String output, long epoch) {
        AgentRunPart part = new AgentRunPart();
        part.setTaskId(71L);
        part.setPartType("tool");
        part.setToolName(toolName);
        part.setStatus(status);
        part.setInputJson(input);
        part.setOutputText(output);
        part.setMetadata("{\"executionEpoch\":" + epoch + "}");
        return part;
    }
}
