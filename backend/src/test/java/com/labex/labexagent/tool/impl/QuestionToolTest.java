package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionToolTest {
    @Test
    void definitionRequiresQuestionSummaryAndOptionsToFollowVisibleLanguage() {
        QuestionTool tool = new QuestionTool();

        String description = tool.definition().getDescription();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.definition().getInputSchema().get("properties");

        assertThat(description).contains("same visible language as the user's latest message");
        assertThat(properties.get("question").toString()).contains("same visible language");
        assertThat(properties.get("summary").toString()).contains("same visible language");
        assertThat(properties.get("options").toString()).contains("same visible language");
    }
}
