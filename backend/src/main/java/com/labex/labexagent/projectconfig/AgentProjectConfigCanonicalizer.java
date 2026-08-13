package com.labex.labexagent.projectconfig;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Produces a deterministic canonical serialization and a SHA-256 digest for a project
 * configuration document.
 *
 * <p>Rules:
 * <ul>
 *   <li>Object keys are sorted recursively in natural (Unicode) order; arrays keep their order,
 *       so reordering an array is a semantic change.</li>
 *   <li>Line endings are normalized to {@code \n} (CRLF/CR input become LF).</li>
 *   <li>The digest is the lowercase hex SHA-256 over the UTF-8 canonical text.</li>
 * </ul>
 *
 * <p>Accepted number-format limitation: JSON has a single number type, but this canonicalizer
 * preserves Gson's normalized literal form. A value written as {@code 1} and the numerically
 * equal value written as {@code 1.0} therefore produce different canonical text and different
 * digests. Whichever literal the file uses, the same literal yields a stable digest.
 */
public final class AgentProjectConfigCanonicalizer {

    private static final Gson GSON = new Gson();

    private AgentProjectConfigCanonicalizer() {
    }

    /** Returns a deep copy of the object with all keys sorted recursively. */
    public static JsonObject canonicalObject(JsonObject input) {
        return (JsonObject) canonicalize(input);
    }

    /** Serializes the element deterministically with recursively sorted object keys. */
    public static String canonicalJson(JsonElement input) {
        return GSON.toJson(canonicalize(input));
    }

    /** Normalizes CRLF and CR line endings to LF. */
    public static String normalizeLineEndings(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Returns the lowercase hex SHA-256 of the UTF-8 encoding of the text. */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Composes one deterministic canonical document: the canonical manifest followed by each
     * canonical child (sorted by reference path). Skill markdown children are appended as
     * line-ending-normalized text. Formatting-only differences (whitespace, object key order,
     * line endings) produce the same output; semantic differences change it.
     */
    public static String canonicalDocument(String manifestCanonicalJson, Map<String, String> childCanonicalByRef) {
        List<String> refs = new ArrayList<>(childCanonicalByRef.keySet());
        Collections.sort(refs);
        StringBuilder builder = new StringBuilder(manifestCanonicalJson.length() + 64);
        builder.append(manifestCanonicalJson).append('\n');
        for (String ref : refs) {
            builder.append(childCanonicalByRef.get(ref)).append('\n');
        }
        return builder.toString();
    }

    private static JsonElement canonicalize(JsonElement element) {
        if (element.isJsonObject()) {
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), canonicalize(entry.getValue()));
            }
            JsonObject result = new JsonObject();
            sorted.forEach(result::add);
            return result;
        }
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                result.add(canonicalize(item));
            }
            return result;
        }
        return element;
    }
}
