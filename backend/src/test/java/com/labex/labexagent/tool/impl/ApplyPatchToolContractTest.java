package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ApplyPatchToolContractTest {
    @Test
    void rejectsCanonicalPatchWithoutTheSchemaRequiredOperation() throws Exception {
        JsonObject arguments = JsonParser.parseString("""
                {"changes":[{"path":"src/Main.java","content":"class Main {}"}]}
                """).getAsJsonObject();

        var result = new ApplyPatchTool(null).execute(null, arguments);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).isEqualTo("operation is required");
    }
}
