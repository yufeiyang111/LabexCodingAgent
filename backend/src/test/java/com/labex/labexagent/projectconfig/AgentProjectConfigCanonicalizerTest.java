package com.labex.labexagent.projectconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentProjectConfigCanonicalizerTest {

    @Test
    void canonicalJsonSortsObjectKeysRecursively() {
        String first = AgentProjectConfigCanonicalizer.canonicalJson(
                JsonParser.parseString("{\"b\":1,\"a\":2,\"nested\":{\"z\":1,\"y\":[{\"d\":4,\"c\":3}]}}"));
        String second = AgentProjectConfigCanonicalizer.canonicalJson(
                JsonParser.parseString("{\"a\":2,\"nested\":{\"y\":[{\"c\":3,\"d\":4}],\"z\":1},\"b\":1}"));
        assertEquals("{\"a\":2,\"b\":1,\"nested\":{\"y\":[{\"c\":3,\"d\":4}],\"z\":1}}", first);
        assertEquals(first, second);
    }

    @Test
    void canonicalJsonIsIndependentOfWhitespaceAndIndentation() {
        String first = AgentProjectConfigCanonicalizer.canonicalJson(
                JsonParser.parseString("{\"a\":1,\"b\":[true,null,\"x\"]}"));
        String second = AgentProjectConfigCanonicalizer.canonicalJson(
                JsonParser.parseString("{\n  \"b\": [ true, null, \"x\" ],\n  \"a\" : 1\n}"));
        assertEquals(first, second);
    }

    @Test
    void normalizeLineEndingsConvertsCrlfAndCrToLf() {
        assertEquals("a\nb\nc", AgentProjectConfigCanonicalizer.normalizeLineEndings("a\r\nb\rc"));
        assertEquals("line\n", AgentProjectConfigCanonicalizer.normalizeLineEndings("line\r\n"));
    }

    @Test
    void sha256HexProducesLowercaseHexSha256() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                AgentProjectConfigCanonicalizer.sha256Hex("hello"));
    }

    @Test
    void formattingOnlyChangesKeepTheSameDocumentDigest() {
        Map<String, String> children = Map.of(
                "agents/main.json", "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\"}",
                "skills/backend.md", "# Backend\n\nInstructions.");
        String compact = AgentProjectConfigCanonicalizer.canonicalDocument(
                AgentProjectConfigCanonicalizer.canonicalJson(
                        JsonParser.parseString("{\"schemaVersion\":1,\"defaultAgent\":\"agents/main.json\","
                                + "\"agents\":[\"agents/main.json\"],\"runtimeProfile\":\"strict\"}")),
                children);
        String formatted = AgentProjectConfigCanonicalizer.canonicalDocument(
                AgentProjectConfigCanonicalizer.canonicalJson(
                        JsonParser.parseString("{\n  \"runtimeProfile\" : \"strict\",\n"
                                + "  \"schemaVersion\": 1,\n"
                                + "  \"agents\": [\"agents/main.json\"],\n"
                                + "  \"defaultAgent\": \"agents/main.json\"\n}")),
                children);
        assertEquals(AgentProjectConfigCanonicalizer.sha256Hex(compact),
                AgentProjectConfigCanonicalizer.sha256Hex(formatted));
    }

    @Test
    void semanticChangesChangeTheDocumentDigest() {
        Map<String, String> children = Map.of("agents/main.json", "{\"id\":\"main\"}");
        String before = AgentProjectConfigCanonicalizer.canonicalDocument(
                AgentProjectConfigCanonicalizer.canonicalJson(
                        JsonParser.parseString("{\"schemaVersion\":1,\"defaultAgent\":\"agents/main.json\"}")),
                children);
        Map<String, String> changedChild = Map.of("agents/main.json", "{\"id\":\"other\"}");
        String after = AgentProjectConfigCanonicalizer.canonicalDocument(
                AgentProjectConfigCanonicalizer.canonicalJson(
                        JsonParser.parseString("{\"schemaVersion\":1,\"defaultAgent\":\"agents/main.json\"}")),
                changedChild);
        assertNotEquals(AgentProjectConfigCanonicalizer.sha256Hex(before),
                AgentProjectConfigCanonicalizer.sha256Hex(after));
    }

    @Test
    void numberLiteralFormsProduceDifferentCanonicalText() {
        String integral = AgentProjectConfigCanonicalizer.canonicalJson(JsonParser.parseString("{\"n\":1}"));
        String decimal = AgentProjectConfigCanonicalizer.canonicalJson(JsonParser.parseString("{\"n\":1.0}"));
        assertNotEquals(integral, decimal);
    }
}
