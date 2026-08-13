package com.labex.labexagent.projectconfig;

import com.google.gson.JsonObject;
import java.util.Map;

/**
 * Immutable, validated projection of one project configuration package: the parsed manifest,
 * the resolved declared child documents (JSON children as canonical objects, skill markdown as
 * normalized text), the full canonical serialization and its SHA-256 digest.
 *
 * <p>Only files declared in or traceable from {@code agent.json} are present. Formatting-only
 * edits to the package keep the same {@link #sha256Digest()}; semantic edits change it.
 *
 * <p>The child maps are unmodifiable copies. The {@link #manifest()} and the values of
 * {@link #childDocuments()} are fresh canonical copies of the parsed documents, but they are
 * still mutable {@code JsonObject}s: callers must not mutate them, or the digest will no
 * longer match the returned document.
 */
public record AgentProjectConfigDocument(
        String schemaVersion,
        String manifestPath,
        JsonObject manifest,
        Map<String, JsonObject> childDocuments,
        Map<String, String> childTexts,
        String canonicalJson,
        String sha256Digest) {
}
