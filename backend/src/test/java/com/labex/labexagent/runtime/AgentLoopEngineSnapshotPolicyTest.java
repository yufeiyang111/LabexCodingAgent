package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.tool.ToolResult;
import org.junit.jupiter.api.Test;

class AgentLoopEngineSnapshotPolicyTest {

    @Test
    void recordsSnapshotDiffsForFailedCommandTools() {
        ToolResult failedCommand = ToolResult.fromProcessExit(1, "tool changed a file before failing");

        assertTrue(AgentLoopEngine.shouldRecordSnapshotDiff(true, failedCommand));
    }

    @Test
    void doesNotRecordSnapshotsForPendingApproval() {
        ToolResult pendingApproval = ToolResult.approvalRequired("approval needed", "command");

        assertFalse(AgentLoopEngine.shouldRecordSnapshotDiff(true, pendingApproval));
    }
}
