package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
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
}