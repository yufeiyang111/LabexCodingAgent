package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentContextToolSelectionTest {
    @Test
    void executableToolsMustBelongToTheSchemasSelectedForThisRun() {
        AgentContext context = new AgentContext(null, null, null, null, null, null,
                List.of(), List.of(), 0);

        context.setSelectedToolNames(List.of("read_file", "question"));

        assertTrue(context.isToolSelected("read_file"));
        assertFalse(context.isToolSelected("write_file"));
        assertFalse(context.isToolSelected(null));
    }
}
