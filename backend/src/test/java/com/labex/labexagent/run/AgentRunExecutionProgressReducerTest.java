package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class AgentRunExecutionProgressReducerTest {

    private final AgentRunExecutionProgressReducer reducer = new AgentRunExecutionProgressReducer();

    @Test
    void rebuildsWriteAndTrustedVerificationProgressFromToolOutcomes() {
        JsonObject writeArgs = new JsonObject();
        writeArgs.addProperty("file_path", "src/Main.java");

        AgentRunExecutionProgressReducer.State afterWrite = reducer.apply(
                reducer.initial(), "write_file", writeArgs, "completed", "saved");

        assertThat(afterWrite.stage()).isEqualTo("implement");
        assertThat(afterWrite.writeCount()).isEqualTo(1);
        assertThat(afterWrite.unverifiedChangeTargets()).containsExactly("src/Main.java");

        AgentRunExecutionProgressReducer.State afterTests = reducer.apply(
                afterWrite, "run_tests", new JsonObject(), "completed", "exit=0");

        assertThat(afterTests.stage()).isEqualTo("verify");
        assertThat(afterTests.verificationCount()).isEqualTo(1);
        assertThat(afterTests.trustedVerificationSources()).containsExactly("run_tests");
        assertThat(afterTests.unverifiedChangeTargets()).isEmpty();
        assertThat(afterTests.hasUnverifiedChanges()).isFalse();
    }

    @Test
    void manualReadVerifiesOnlyTheMatchingChangedTarget() {
        JsonObject firstWrite = new JsonObject();
        firstWrite.addProperty("file_path", "package.json");
        JsonObject secondWrite = new JsonObject();
        secondWrite.addProperty("file_path", "src/Main.java");
        AgentRunExecutionProgressReducer.State state = reducer.apply(
                reducer.initial(), "write_file", firstWrite, "completed", "saved");
        state = reducer.apply(state, "edit_file", secondWrite, "completed", "saved");

        JsonObject readArgs = new JsonObject();
        readArgs.addProperty("file_path", "package.json");
        state = reducer.apply(state, "read_file", readArgs, "completed",
                "[read_file path=package.json sha256=abc]\n{}");

        assertThat(state.verificationCount()).isEqualTo(1);
        assertThat(state.trustedVerificationSources()).containsExactly("read_file");
        assertThat(state.unverifiedChangeTargets()).containsExactly("src/Main.java");
        assertThat(state.hasUnverifiedChanges()).isTrue();
    }

    @Test
    void completedTransportWithNonzeroExitMetadataMovesProgressIntoRepair() {
        JsonObject metadata = JsonParser.parseString(
                "{\"failureClass\":\"non_zero_exit\",\"execution\":{\"status\":\"failed\",\"exitCode\":2}}")
                .getAsJsonObject();

        AgentRunExecutionProgressReducer.State failed = reducer.apply(
                reducer.initial(), "shell", new JsonObject(), "completed", "exit=2", metadata);

        assertThat(failed.stage()).isEqualTo("repair");
        assertThat(AgentRunExecutionProgressReducer.effectiveToolStatus("completed", metadata)).isEqualTo("error");
    }
    @Test
    void waitingAndInterruptedToolPartsDoNotInventProgress() {
        AgentRunExecutionProgressReducer.State initial = reducer.initial();

        for (String status : new String[]{"pending", "running", "waiting_approval", "waiting_user", "skipped", "interrupted"}) {
            AgentRunExecutionProgressReducer.State unchanged = reducer.apply(
                    initial, "write_file", new JsonObject(), status, "not settled");
            assertThat(unchanged).isEqualTo(initial);
        }
    }

    @Test
    void explicitFailureMovesToRepairWithoutIncrementingWriteOrVerificationCounts() {
        JsonObject args = new JsonObject();
        args.addProperty("file_path", "src/Main.java");

        AgentRunExecutionProgressReducer.State failed = reducer.apply(
                reducer.initial(), "write_file", args, "error", "permission denied");

        assertThat(failed.stage()).isEqualTo("repair");
        assertThat(failed.writeCount()).isZero();
        assertThat(failed.verificationCount()).isZero();
        assertThat(failed.unverifiedChangeTargets()).isEmpty();
    }

    @Test
    void mismatchedWorkspacePostconditionKeepsShellMutationUnverifiedAndMovesToRepair() {
        JsonObject metadata = JsonParser.parseString("""
                {
                  "workspaceMutation": {
                    "state": "applied",
                    "targets": [{
                      "path": "skills/SKILL.md",
                      "operation": "delete",
                      "after": {"state": "present", "verified": false}
                    }]
                  },
                  "workspaceVerification": {
                    "state": "mismatch",
                    "targets": [{
                      "path": "skills/SKILL.md",
                      "expectedState": "absent",
                      "observedState": "present"
                    }]
                  }
                }
                """).getAsJsonObject();

        AgentRunExecutionProgressReducer.State mismatch = reducer.apply(
                reducer.initial(), "shell", new JsonObject(), "completed", "exit=0", metadata);

        assertThat(mismatch.stage()).isEqualTo("repair");
        assertThat(mismatch.writeCount()).isEqualTo(1);
        assertThat(mismatch.verificationCount()).isZero();
        assertThat(mismatch.trustedVerificationSources()).isEmpty();
        assertThat(mismatch.unverifiedChangeTargets()).containsExactly("skills/SKILL.md");
        assertThat(mismatch.hasUnverifiedChanges()).isTrue();
    }

    @Test
    void verifiedWorkspacePostconditionClearsOnlyItsOwnTargetsAndAddsTrustedEvidence() {
        JsonObject metadata = JsonParser.parseString("""
                {
                  "workspaceMutation": {
                    "state": "applied",
                    "targets": [{
                      "path": "skills/SKILL.md",
                      "operation": "delete",
                      "after": {"state": "absent", "verified": true}
                    }]
                  },
                  "workspaceVerification": {
                    "state": "verified",
                    "targets": [{
                      "path": "skills/SKILL.md",
                      "expectedState": "absent",
                      "observedState": "absent"
                    }]
                  }
                }
                """).getAsJsonObject();
        AgentRunExecutionProgressReducer.State prior = new AgentRunExecutionProgressReducer.State(
                "implement", 1, 0, true, java.util.Set.of(), java.util.Set.of("src/Other.java"));

        AgentRunExecutionProgressReducer.State verified = reducer.apply(
                prior, "shell", new JsonObject(), "completed", "exit=0", metadata);

        assertThat(verified.stage()).isEqualTo("verify");
        assertThat(verified.writeCount()).isEqualTo(2);
        assertThat(verified.verificationCount()).isEqualTo(1);
        assertThat(verified.trustedVerificationSources()).containsExactly("workspace_postcondition");
        assertThat(verified.unverifiedChangeTargets()).containsExactly("src/Other.java");
        assertThat(verified.hasUnverifiedChanges()).isTrue();
    }


    @Test
    void workspaceMetadataWithAnAbsoluteTargetDoesNotEnterProgressProjection() {
        JsonObject metadata = JsonParser.parseString("""
                {
                  "workspaceMutation": {
                    "state": "applied",
                    "targets": [{
                      "path": "C:/private/secret.txt",
                      "operation": "delete",
                      "after": {"state": "absent", "verified": true}
                    }]
                  },
                  "workspaceVerification": {"state": "verified", "targets": []}
                }
                """).getAsJsonObject();

        AgentRunExecutionProgressReducer.State projected = reducer.apply(
                reducer.initial(), "shell", new JsonObject(), "completed", "exit=0", metadata);

        assertThat(projected).isEqualTo(reducer.initial());
    }

}