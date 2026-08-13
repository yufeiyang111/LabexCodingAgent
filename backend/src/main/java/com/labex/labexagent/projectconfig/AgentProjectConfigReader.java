package com.labex.labexagent.projectconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ChildKind;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.LoadedChild;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ValidationError;
import com.labex.labexagent.projectconfig.ProtectedProjectConfigPath.PathViolationException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and validates the protected project configuration package
 * ({@code .labex-agent/project/agent.json} plus only its declared child files) and produces an
 * immutable {@link AgentProjectConfigDocument} with a canonical SHA-256 digest.
 *
 * <p>Any path violation, schema violation, malformed JSON, ceiling violation, secret-looking
 * key or invalid reference fails closed by throwing {@link ConfigReadException} carrying the
 * structured errors. No raw value of a rejected secret-looking key ever appears in an error.
 */
public final class AgentProjectConfigReader {

    public static final String MANIFEST_FILE = AgentProjectConfigValidator.MANIFEST;

    /** Structured, fail-closed read/validation failure. */
    public static final class ConfigReadException extends Exception {
        private final List<ValidationError> errors;

        private ConfigReadException(List<ValidationError> errors) {
            super("project configuration is invalid: " + errors.size() + " violation(s)");
            this.errors = List.copyOf(errors);
        }

        public List<ValidationError> errors() {
            return errors;
        }
    }

    private final long maxFileBytes;

    public AgentProjectConfigReader() {
        this(ProtectedProjectConfigPath.DEFAULT_MAX_FILE_BYTES);
    }

    public AgentProjectConfigReader(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    /** Reads, validates and canonicalizes the project configuration under the owned project root. */
    public AgentProjectConfigDocument read(Path projectRoot) throws ConfigReadException {
        List<ValidationError> errors = new ArrayList<>();
        ProtectedProjectConfigPath boundary;
        try {
            boundary = new ProtectedProjectConfigPath(projectRoot, maxFileBytes);
        } catch (PathViolationException e) {
            errors.add(new ValidationError(e.relativePath(), "", e.reasonCode(), e.getMessage()));
            throw new ConfigReadException(errors);
        }

        String manifestText;
        try {
            manifestText = boundary.readText(MANIFEST_FILE);
        } catch (PathViolationException e) {
            errors.add(new ValidationError(e.relativePath(), "", e.reasonCode(), e.getMessage()));
            throw new ConfigReadException(errors);
        }
        JsonObject manifest = parseObject(MANIFEST_FILE, manifestText, errors);
        if (manifest == null) {
            throw new ConfigReadException(errors);
        }
        errors.addAll(new AgentProjectConfigValidator().validateManifest(manifest));
        if (!errors.isEmpty()) {
            throw new ConfigReadException(errors);
        }

        List<LoadedChild> children = new ArrayList<>();
        collectChildren(boundary, manifest, children, errors);
        if (!errors.isEmpty()) {
            throw new ConfigReadException(errors);
        }
        errors.addAll(new AgentProjectConfigValidator().validate(MANIFEST_FILE, manifest, children));
        if (!errors.isEmpty()) {
            throw new ConfigReadException(errors);
        }
        return buildDocument(boundary, manifest, children);
    }

    private AgentProjectConfigDocument buildDocument(ProtectedProjectConfigPath boundary,
                                                     JsonObject manifest, List<LoadedChild> children) {
        JsonObject canonicalManifest = AgentProjectConfigCanonicalizer.canonicalObject(manifest);
        Map<String, JsonObject> childDocuments = new LinkedHashMap<>();
        Map<String, String> childTexts = new LinkedHashMap<>();
        Map<String, String> canonicalByRef = new HashMap<>();
        for (LoadedChild child : children) {
            if (child.json() != null) {
                JsonObject canonical = AgentProjectConfigCanonicalizer.canonicalObject(child.json());
                childDocuments.put(child.refPath(), canonical);
                canonicalByRef.put(child.refPath(), AgentProjectConfigCanonicalizer.canonicalJson(canonical));
            } else {
                String normalized = AgentProjectConfigCanonicalizer.normalizeLineEndings(child.text()).strip();
                childTexts.put(child.refPath(), normalized);
                canonicalByRef.put(child.refPath(), normalized);
            }
        }
        String canonicalJson = AgentProjectConfigCanonicalizer.canonicalDocument(
                AgentProjectConfigCanonicalizer.canonicalJson(canonicalManifest), canonicalByRef);
        String digest = AgentProjectConfigCanonicalizer.sha256Hex(canonicalJson);
        return new AgentProjectConfigDocument(
                manifest.get("schemaVersion").getAsString(),
                boundary.configDir().resolve(MANIFEST_FILE).toString(),
                canonicalManifest,
                Map.copyOf(childDocuments),
                Map.copyOf(childTexts),
                canonicalJson,
                digest);
    }

    private void collectChildren(ProtectedProjectConfigPath boundary, JsonObject manifest,
                                 List<LoadedChild> out, List<ValidationError> errors) {
        Map<String, String> seenRefs = new HashMap<>();
        collectRefArray(boundary, manifest, "agents", "$.agents", ChildKind.AGENT, out, seenRefs, errors);
        collectRefArray(boundary, manifest, "tools", "$.tools", ChildKind.TOOL, out, seenRefs, errors);
        collectRefArray(boundary, manifest, "mcpServers", "$.mcpServers", ChildKind.MCP, out, seenRefs, errors);
        collectRefArray(boundary, manifest, "skills", "$.skills", ChildKind.SKILL, out, seenRefs, errors);
        if (manifest.has("environment")) {
            JsonElement element = manifest.get("environment");
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    && "environment.json".equals(element.getAsString())) {
                loadChild(boundary, "environment.json", "$.environment", ChildKind.ENVIRONMENT,
                        out, seenRefs, errors);
            }
        }
    }

    private void collectRefArray(ProtectedProjectConfigPath boundary, JsonObject manifest, String key,
                                 String jsonPathPrefix, ChildKind kind, List<LoadedChild> out,
                                 Map<String, String> seenRefs, List<ValidationError> errors) {
        if (!manifest.has(key)) {
            return;
        }
        JsonElement element = manifest.get(key);
        if (!element.isJsonArray()) {
            return;
        }
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                continue;
            }
            loadChild(boundary, item.getAsString(), jsonPathPrefix + "[" + i + "]",
                    kind, out, seenRefs, errors);
        }
    }

    private void loadChild(ProtectedProjectConfigPath boundary, String ref, String jsonPath, ChildKind kind,
                           List<LoadedChild> out, Map<String, String> seenRefs, List<ValidationError> errors) {
        String normalizedRef = ref.replace('\\', '/');
        if (seenRefs.containsKey(normalizedRef)) {
            errors.add(new ValidationError(normalizedRef, jsonPath, AgentProjectConfigValidator.REASON_DUPLICATE_REFERENCE,
                    "duplicate child file reference"));
            return;
        }
        seenRefs.put(normalizedRef, jsonPath);
        try {
            if (!boundary.exists(normalizedRef)) {
                errors.add(new ValidationError(normalizedRef, jsonPath,
                        AgentProjectConfigValidator.REASON_REFERENCE_UNRESOLVABLE,
                        "declared child file does not exist"));
                return;
            }
            String text = boundary.readText(normalizedRef);
            if (kind == ChildKind.SKILL) {
                out.add(LoadedChild.text(normalizedRef, text));
                return;
            }
            JsonObject doc = parseObject(normalizedRef, text, errors);
            if (doc != null) {
                out.add(LoadedChild.json(normalizedRef, kind, doc));
            }
        } catch (PathViolationException e) {
            errors.add(new ValidationError(e.relativePath(), jsonPath, e.reasonCode(), e.getMessage()));
        }
    }

    private JsonObject parseObject(String filePath, String text, List<ValidationError> errors) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                errors.add(new ValidationError(filePath, "$", AgentProjectConfigValidator.REASON_MALFORMED_JSON,
                        "expected a JSON object"));
                return null;
            }
            return element.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            errors.add(new ValidationError(filePath, "$", AgentProjectConfigValidator.REASON_MALFORMED_JSON,
                    "config file is not valid JSON"));
            return null;
        }
    }
}
