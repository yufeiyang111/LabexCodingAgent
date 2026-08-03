package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolCallArgumentsParserTest {
    @Test
    void distinguishesValidObjectFromMissingMalformedAndNonObjectArguments() {
        ToolCallArgumentsParser.ParseResult valid = ToolCallArgumentsParser.parse("{\"path\":\"src\"}");
        ToolCallArgumentsParser.ParseResult missing = ToolCallArgumentsParser.parse("  ");
        ToolCallArgumentsParser.ParseResult malformed = ToolCallArgumentsParser.parse("{\"path\":");
        ToolCallArgumentsParser.ParseResult nonObject = ToolCallArgumentsParser.parse("[\"src\"]");

        assertThat(valid.valid()).isTrue();
        assertThat(valid.arguments().get("path").getAsString()).isEqualTo("src");
        assertThat(missing.status()).isEqualTo(ToolCallArgumentsParser.Status.MISSING);
        assertThat(malformed.status()).isEqualTo(ToolCallArgumentsParser.Status.INVALID_JSON);
        assertThat(nonObject.status()).isEqualTo(ToolCallArgumentsParser.Status.NON_OBJECT);
        assertThat(missing.arguments().size()).isZero();
        assertThat(malformed.arguments().size()).isZero();
        assertThat(nonObject.arguments().size()).isZero();
    }
}
