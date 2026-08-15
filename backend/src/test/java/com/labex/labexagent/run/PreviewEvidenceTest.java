package com.labex.labexagent.run;

import com.labex.entity.AgentPreviewRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewEvidenceTest {

    @Test
    void preservesAnExplicitlyStoppedPreviewInsteadOfRelabelingItAsFailed() {
        AgentPreviewRun run = new AgentPreviewRun();
        run.setStatus("stopped");
        run.setPublicUrl("http://localhost:5000/");

        PreviewEvidence evidence = PreviewEvidence.from(run);

        assertEquals(PreviewEvidence.Status.STOPPED, evidence.status());
        assertFalse(evidence.ready());
        assertTrue(evidence.publicUrl().isBlank());
    }
}
