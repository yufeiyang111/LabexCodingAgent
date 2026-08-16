package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import org.junit.jupiter.api.Test;

class LabexNativeCompletionProjectorTest {

    @Test
    void nativeUnverifiedCompletionKeepsTheModelReplyVisibleWithoutClaimingVerifiedSuccess() {
        AgentRunFinalizer.CompletionAssessment rejected = new AgentRunFinalizer.CompletionAssessment(
                false, null, "completion_evidence_unsatisfied", "record a successful verification");

        LabexNativeCompletionProjector.Projection projection = new LabexNativeCompletionProjector()
                .project(AgentRuntimeProfile.LABEX_NATIVE, rejected);

        assertEquals(LabexNativeCompletionProjector.Disposition.VISIBLE_UNVERIFIED, projection.disposition());
        assertTrue(projection.publishFinal());
        assertFalse(projection.requiresModelRecovery());
        assertFalse(projection.completesTask());
    }

    @Test
    void legacyUnverifiedCompletionKeepsTheExistingRecoveryGate() {
        AgentRunFinalizer.CompletionAssessment rejected = new AgentRunFinalizer.CompletionAssessment(
                false, null, "completion_evidence_unsatisfied", "record a successful verification");

        LabexNativeCompletionProjector.Projection projection = new LabexNativeCompletionProjector()
                .project(AgentRuntimeProfile.LABEX_LEGACY, rejected);

        assertEquals(LabexNativeCompletionProjector.Disposition.BLOCKED, projection.disposition());
        assertFalse(projection.publishFinal());
        assertTrue(projection.requiresModelRecovery());
        assertFalse(projection.completesTask());
    }

    @Test
    void nativeDisablesLegacyTextFormatAndIntentGatesButKeepsTheEvidenceProjection() {
        LabexNativeCompletionProjector projector = new LabexNativeCompletionProjector();
        AgentRunFinalizer.CompletionAssessment accepted = new AgentRunFinalizer.CompletionAssessment(
                true, null, "satisfied", "");

        assertFalse(projector.usesLegacyTextFinalGuards(AgentRuntimeProfile.LABEX_NATIVE));
        assertTrue(projector.usesLegacyTextFinalGuards(AgentRuntimeProfile.LABEX_LEGACY));
        assertEquals(LabexNativeCompletionProjector.Disposition.ACCEPTED,
                projector.project(AgentRuntimeProfile.LABEX_NATIVE, accepted).disposition());
    }
}
