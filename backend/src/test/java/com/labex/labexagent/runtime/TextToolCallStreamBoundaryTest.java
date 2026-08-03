package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextToolCallStreamBoundaryTest {
    @Test
    void ordinaryVisibleTextPassesThroughWithoutWaitingForCompletion() {
        List<String> visible = new ArrayList<>();
        TextToolCallStreamBoundary boundary = new TextToolCallStreamBoundary(visible::add);

        boundary.push("正常回答");

        assertEquals(List.of("正常回答"), visible);
    }

    @Test
    void splitExplicitToolEnvelopeNeverLeaksToVisibleProjection() {
        List<String> visible = new ArrayList<>();
        TextToolCallStreamBoundary boundary = new TextToolCallStreamBoundary(visible::add);

        boundary.push("  <to");
        boundary.push("ol_call>{\"name\":\"list_files\",\"arguments\":{}}");
        boundary.push("</tool_call>");
        boundary.finish();

        assertTrue(visible.isEmpty());
    }

    @Test
    void incompleteExplicitEnvelopeIsAlsoHeldBack() {
        List<String> visible = new ArrayList<>();
        TextToolCallStreamBoundary boundary = new TextToolCallStreamBoundary(visible::add);

        boundary.push("<invoke name=\"read_file\">");
        boundary.push("<parameter name=\"file_path\">README.md");
        boundary.finish();

        assertTrue(visible.isEmpty());
    }

    @Test
    void longerNamesThatOnlyShareTheProtocolPrefixRemainVisible() {
        List<String> visible = new ArrayList<>();
        TextToolCallStreamBoundary boundary = new TextToolCallStreamBoundary(visible::add);

        boundary.push("<tool_calligraphy>ordinary</tool_calligraphy>");
        boundary.push("```tool_call_example\nplain code\n```");

        assertEquals(List.of(
                "<tool_calligraphy>ordinary</tool_calligraphy>",
                "```tool_call_example\nplain code\n```"), visible);
    }
    @Test
    void tagLikeOrdinaryTextFlushesAsSoonAsItIsDisambiguated() {
        List<String> visible = new ArrayList<>();
        TextToolCallStreamBoundary boundary = new TextToolCallStreamBoundary(visible::add);

        boundary.push("<toolbox>");
        boundary.push("普通正文");

        assertEquals(List.of("<toolbox>", "普通正文"), visible);
    }
}
