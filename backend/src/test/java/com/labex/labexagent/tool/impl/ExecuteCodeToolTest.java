package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ExecuteCodeToolTest {

    @Test
    void rejectsUnsupportedLanguageBeforeTouchingTheWorker() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("language", "ruby");
        args.addProperty("code", "puts 'hello'");

        var result = new ExecuteCodeTool(null).execute(null, args);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).contains("code=UNSUPPORTED_LANGUAGE")
                .contains("supported_languages=python,javascript");
    }
}
