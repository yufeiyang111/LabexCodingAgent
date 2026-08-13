package com.labex.labexagent.projectconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed schema and reference validation for the {@code .labex-agent/project} configuration
 * package. Every rule returns one or more structured {@link ValidationError}s carrying a file
 * path (relative to the project config directory), a JSON path, a stable reason code and a
 * safe message. Messages never include the raw value of a rejected secret-looking key.
 */
public final class AgentProjectConfigValidator {

    /** Logical file name of the entry manifest. */
    public static final String MANIFEST = "agent.json";

    /** The only schema version this validator understands. */
    public static final long SUPPORTED_SCHEMA_VERSION = 1;

    public static final String REASON_PATH_INVALID = "PATH_INVALID";
    public static final String REASON_PATH_ABSOLUTE = "PATH_ABSOLUTE";
    public static final String REASON_PATH_TRAVERSAL = "PATH_TRAVERSAL";
    public static final String REASON_PATH_ESCAPE = "PATH_ESCAPE";
    public static final String REASON_PATH_SYMLINK = "PATH_SYMLINK";
    public static final String REASON_PATH_OUTSIDE_PROJECT = "PATH_OUTSIDE_PROJECT";
    public static final String REASON_PATH_SIZE_LIMIT = "PATH_SIZE_LIMIT";
    public static final String REASON_PATH_MISSING = "PATH_MISSING";
    public static final String REASON_MALFORMED_JSON = "MALFORMED_JSON";
    public static final String REASON_MISSING_SCHEMA_VERSION = "MISSING_SCHEMA_VERSION";
    public static final String REASON_UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION";
    public static final String REASON_UNKNOWN_KEY = "UNKNOWN_KEY";
    public static final String REASON_SECRET_KEY = "SECRET_KEY";
    public static final String REASON_SECRET_VALUE = "SECRET_VALUE";
    public static final String REASON_TYPE_MISMATCH = "TYPE_MISMATCH";
    public static final String REASON_MISSING_REQUIRED_KEY = "MISSING_REQUIRED_KEY";
    public static final String REASON_INVALID_ENUM_VALUE = "INVALID_ENUM_VALUE";
    public static final String REASON_INVALID_VALUE = "INVALID_VALUE";
    public static final String REASON_PROFILE_UNSUPPORTED = "PROFILE_UNSUPPORTED";
    public static final String REASON_PROFILE_DENIED = "PROFILE_DENIED";
    public static final String REASON_CAPABILITY_ABOVE_CEILING = "CAPABILITY_ABOVE_CEILING";
    public static final String REASON_DUPLICATE_DEFINITION = "DUPLICATE_DEFINITION";
    public static final String REASON_DUPLICATE_REFERENCE = "DUPLICATE_REFERENCE";
    public static final String REASON_REFERENCE_UNRESOLVABLE = "REFERENCE_UNRESOLVABLE";
    public static final String REASON_REFERENCE_INVALID = "REFERENCE_INVALID";
    public static final String REASON_DEFAULT_AGENT_UNDECLARED = "DEFAULT_AGENT_UNDECLARED";

    /** Runtime profiles a project manifest may declare. {@code full-access} still requires the
     * user-originated per-task decision enforced by the Worker router. */
    public static final Set<String> SUPPORTED_RUNTIME_PROFILES =
            Set.of("strict", "integrated-wsl", "integrated-windows", "full-access");

    /** Diagnostic-only profile that must never be selected from a project file. */
    public static final String DENIED_PROFILE = "unsafe-local";

    private static final Pattern SECRET_KEY_PATTERN = Pattern.compile(
            "(?i).*(api[_-]?key|access[_-]?key|secret|token|password|passwd|authorization|"
                    + "auth[_-]?header|credential|private[_-]?key|refresh[_-]?token|pwd).*");

    private static final Set<String> MANIFEST_KEYS = Set.of(
            "schemaVersion", "defaultAgent", "agents", "tools", "mcpServers", "skills",
            "models", "environment", "runtimeProfile", "networkPolicy",
            "verificationPolicy", "capabilities");
    private static final Set<String> NETWORK_POLICY_KEYS =
            Set.of("allowPublicDownloads", "allowLocalhost", "allowLan");
    private static final Set<String> VERIFICATION_POLICY_KEYS =
            Set.of("requireTests", "requireLint", "requireBuild");
    private static final Set<String> CAPABILITY_POLICY_KEYS = Set.of(
            "enabledTools", "deniedTools", "allowSecretAccess", "allowMetadataAccess",
            "allowCrossUserData", "allowProtectedConfigWrites", "allowHostMaintenance",
            "allowWslShutdown");
    private static final Set<String> HARD_DENIED_CAPABILITIES = Set.of(
            "allowSecretAccess", "allowMetadataAccess", "allowCrossUserData",
            "allowProtectedConfigWrites", "allowHostMaintenance", "allowWslShutdown");
    private static final Set<String> AGENT_CHILD_KEYS =
            Set.of("id", "name", "description", "instructions", "modelRef", "tools");
    private static final Set<String> TOOL_CHILD_KEYS =
            Set.of("name", "script", "description", "timeoutSeconds", "outputLimitBytes");
    private static final Set<String> MCP_CHILD_KEYS =
            Set.of("id", "name", "command", "args", "credentialAlias", "envNames");
    private static final Set<String> ENVIRONMENT_CHILD_KEYS =
            Set.of("variables", "installPolicy");
    private static final Set<String> INSTALL_POLICIES = Set.of("project-only", "user-only");

    /** One structured, redaction-safe validation failure. */
    public record ValidationError(String filePath, String jsonPath, String reasonCode, String message) {
    }

    /** Document kind of a declared child file. */
    public enum ChildKind { AGENT, TOOL, MCP, ENVIRONMENT, SKILL }

    /** A declared child file already read from disk and parsed for validation. */
    public static final class LoadedChild {
        private final String refPath;
        private final ChildKind kind;
        private final JsonObject json;
        private final String text;

        private LoadedChild(String refPath, ChildKind kind, JsonObject json, String text) {
            this.refPath = refPath;
            this.kind = kind;
            this.json = json;
            this.text = text;
        }

        public static LoadedChild json(String refPath, ChildKind kind, JsonObject json) {
            return new LoadedChild(refPath, kind, json, null);
        }

        public static LoadedChild text(String refPath, String text) {
            return new LoadedChild(refPath, ChildKind.SKILL, null, text);
        }

        public String refPath() {
            return refPath;
        }

        public ChildKind kind() {
            return kind;
        }

        public JsonObject json() {
            return json;
        }

        public String text() {
            return text;
        }
    }

    /** Validates the entry manifest alone. */
    public List<ValidationError> validateManifest(JsonObject manifest) {
        List<ValidationError> errors = new ArrayList<>();
        if (manifest == null) {
            errors.add(new ValidationError(MANIFEST, "$", REASON_MALFORMED_JSON,
                    "manifest must be a JSON object"));
            return errors;
        }
        checkKeys(manifest, MANIFEST_KEYS, "$", MANIFEST, errors);
        validateSchemaVersion(manifest, errors);
        validateRequiredString(manifest, "defaultAgent", "$.defaultAgent", MANIFEST, errors);
        validateRefArray(manifest, "agents", true, errors);
        validateRefArray(manifest, "tools", false, errors);
        validateRefArray(manifest, "mcpServers", false, errors);
        validateRefArray(manifest, "skills", false, errors);
        validateModelRefs(manifest, errors);
        validateEnvironmentRef(manifest, errors);
        validateRuntimeProfile(manifest, errors);
        validateBooleanPolicyObject(manifest, "networkPolicy", NETWORK_POLICY_KEYS, errors);
        validateBooleanPolicyObject(manifest, "verificationPolicy", VERIFICATION_POLICY_KEYS, errors);
        validateCapabilities(manifest, errors);
        if (hasNonBlankString(manifest, "defaultAgent") && manifest.has("agents")
                && manifest.get("agents").isJsonArray()) {
            String defaultAgent = manifest.get("defaultAgent").getAsString();
            if (!containsString(manifest.getAsJsonArray("agents"), defaultAgent)) {
                errors.add(new ValidationError(MANIFEST, "$.defaultAgent", REASON_DEFAULT_AGENT_UNDECLARED,
                        "defaultAgent must be listed in the agents array"));
            }
        }
        return errors;
    }

    /** Validates declared child documents plus cross-file references and duplicate definitions.
     * An agent's {@code tools} array is a reference allowlist: every entry must name a tool
     * declared by one of the {@code tools/*.json} documents. */
    public List<ValidationError> validate(String manifestPath, JsonObject manifest, List<LoadedChild> children) {
        List<ValidationError> errors = new ArrayList<>();
        if (children == null) {
            return errors;
        }
        Set<String> declaredModelRefs = collectModelRefs(manifest);
        Set<String> declaredToolNames = collectToolNames(children);
        Map<String, String> firstDefinition = new HashMap<>();
        for (LoadedChild child : children) {
            validateRefFormat(child, errors);
            if (child.kind() == ChildKind.SKILL) {
                String slug = skillSlug(child.refPath());
                if (slug != null) {
                    checkDuplicateDefinition(firstDefinition, "skill:" + slug, child,
                            "skill slug '" + slug + "'", errors);
                }
                continue;
            }
            JsonObject doc = child.json();
            if (doc == null) {
                continue;
            }
            switch (child.kind()) {
                case AGENT -> {
                    checkKeys(doc, AGENT_CHILD_KEYS, "$", child.refPath(), errors);
                    validateRequiredString(doc, "id", "$.id", child.refPath(), errors);
                    validateOptionalString(doc, "name", "$.name", child.refPath(), errors);
                    validateOptionalString(doc, "description", "$.description", child.refPath(), errors);
                    validateOptionalString(doc, "instructions", "$.instructions", child.refPath(), errors);
                    validateOptionalString(doc, "modelRef", "$.modelRef", child.refPath(), errors);
                    validateStringArray(doc, "tools", "$.tools", child.refPath(), errors);
                    if (doc.has("tools") && doc.get("tools").isJsonArray()) {
                        JsonArray toolRefs = doc.getAsJsonArray("tools");
                        for (int i = 0; i < toolRefs.size(); i++) {
                            JsonElement toolRef = toolRefs.get(i);
                            if (isNonBlankString(toolRef) && !declaredToolNames.contains(toolRef.getAsString())) {
                                errors.add(new ValidationError(child.refPath(), "$.tools[" + i + "]",
                                        REASON_REFERENCE_UNRESOLVABLE,
                                        "referenced tool is not declared in any tools/*.json definition"));
                            }
                        }
                    }
                    if (hasNonBlankString(doc, "id")) {
                        checkDuplicateDefinition(firstDefinition, "agent:" + doc.get("id").getAsString(),
                                child, "agent id", errors);
                    }
                    if (hasNonBlankString(doc, "modelRef")
                            && !declaredModelRefs.contains(doc.get("modelRef").getAsString())) {
                        errors.add(new ValidationError(child.refPath(), "$.modelRef",
                                REASON_REFERENCE_UNRESOLVABLE,
                                "referenced model is not declared in the manifest"));
                    }
                }
                case TOOL -> {
                    checkKeys(doc, TOOL_CHILD_KEYS, "$", child.refPath(), errors);
                    validateRequiredString(doc, "name", "$.name", child.refPath(), errors);
                    validateRequiredString(doc, "script", "$.script", child.refPath(), errors);
                    validateOptionalString(doc, "description", "$.description", child.refPath(), errors);
                    validateNonNegativeNumber(doc, "timeoutSeconds", "$.timeoutSeconds", child.refPath(), errors);
                    validateNonNegativeNumber(doc, "outputLimitBytes", "$.outputLimitBytes", child.refPath(), errors);
                    if (hasNonBlankString(doc, "name")) {
                        checkDuplicateDefinition(firstDefinition, "tool:" + doc.get("name").getAsString(),
                                child, "tool name", errors);
                    }
                }
                case MCP -> {
                    checkKeys(doc, MCP_CHILD_KEYS, "$", child.refPath(), errors);
                    validateRequiredString(doc, "id", "$.id", child.refPath(), errors);
                    validateRequiredString(doc, "command", "$.command", child.refPath(), errors);
                    validateOptionalString(doc, "name", "$.name", child.refPath(), errors);
                    validateStringArray(doc, "args", "$.args", child.refPath(), errors);
                    validateOptionalString(doc, "credentialAlias", "$.credentialAlias", child.refPath(), errors);
                    validateNamesArray(doc, "envNames", "$.envNames", child.refPath(), errors);
                    if (hasNonBlankString(doc, "id")) {
                        checkDuplicateDefinition(firstDefinition, "mcp:" + doc.get("id").getAsString(),
                                child, "MCP server id", errors);
                    }
                }
                case ENVIRONMENT -> {
                    checkKeys(doc, ENVIRONMENT_CHILD_KEYS, "$", child.refPath(), errors);
                    validateNamesArray(doc, "variables", "$.variables", child.refPath(), errors);
                    validateEnum(doc, "installPolicy", "$.installPolicy", child.refPath(),
                            INSTALL_POLICIES, errors);
                }
                default -> {
                    errors.add(new ValidationError(child.refPath(), "$", REASON_REFERENCE_INVALID,
                            "unsupported child document kind"));
                }
            }
        }
        return errors;
    }

    private void validateSchemaVersion(JsonObject manifest, List<ValidationError> errors) {
        JsonElement value = manifest.get("schemaVersion");
        if (value == null) {
            errors.add(new ValidationError(MANIFEST, "$.schemaVersion", REASON_MISSING_SCHEMA_VERSION,
                    "schemaVersion is required"));
            return;
        }
        if (!isNumber(value)) {
            errors.add(new ValidationError(MANIFEST, "$.schemaVersion", REASON_TYPE_MISMATCH,
                    "schemaVersion must be a number"));
            return;
        }
        double asDouble = value.getAsDouble();
        long asLong = value.getAsLong();
        if (asDouble != asLong || asLong != SUPPORTED_SCHEMA_VERSION) {
            errors.add(new ValidationError(MANIFEST, "$.schemaVersion", REASON_UNSUPPORTED_SCHEMA_VERSION,
                    "schemaVersion is not supported"));
        }
    }

    private void validateRequiredString(JsonObject obj, String key, String jsonPath, String filePath,
                                        List<ValidationError> errors) {
        if (!obj.has(key)) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_MISSING_REQUIRED_KEY,
                    "required key is missing: " + key));
            return;
        }
        if (!isNonBlankString(obj.get(key))) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_TYPE_MISMATCH,
                    key + " must be a non-empty string"));
        }
    }

    private void validateOptionalString(JsonObject obj, String key, String jsonPath, String filePath,
                                        List<ValidationError> errors) {
        if (!obj.has(key)) {
            return;
        }
        if (!isNonBlankString(obj.get(key))) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_TYPE_MISMATCH,
                    key + " must be a non-empty string"));
        }
    }

    private void validateRefArray(JsonObject obj, String key, boolean required, List<ValidationError> errors) {
        String jsonPath = "$." + key;
        if (!obj.has(key)) {
            if (required) {
                errors.add(new ValidationError(MANIFEST, jsonPath, REASON_MISSING_REQUIRED_KEY,
                        "required key is missing: " + key));
            }
            return;
        }
        JsonElement element = obj.get(key);
        if (!element.isJsonArray()) {
            errors.add(new ValidationError(MANIFEST, jsonPath, REASON_TYPE_MISMATCH,
                    key + " must be an array of file references"));
            return;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty() && required) {
            errors.add(new ValidationError(MANIFEST, jsonPath, REASON_REFERENCE_INVALID,
                    key + " must declare at least one reference"));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            String itemPath = jsonPath + "[" + i + "]";
            if (!isNonBlankString(item)) {
                errors.add(new ValidationError(MANIFEST, itemPath, REASON_TYPE_MISMATCH,
                        key + " entries must be non-empty strings"));
                continue;
            }
            if (!seen.add(item.getAsString())) {
                errors.add(new ValidationError(MANIFEST, itemPath, REASON_DUPLICATE_REFERENCE,
                        "duplicate reference in " + key));
            }
        }
    }

    private void validateModelRefs(JsonObject manifest, List<ValidationError> errors) {
        if (!manifest.has("models")) {
            return;
        }
        JsonElement element = manifest.get("models");
        if (!element.isJsonArray()) {
            errors.add(new ValidationError(MANIFEST, "$.models", REASON_TYPE_MISMATCH,
                    "models must be an array of catalog identifiers"));
            return;
        }
        JsonArray array = element.getAsJsonArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            String itemPath = "$.models[" + i + "]";
            if (!isNonBlankString(item)) {
                errors.add(new ValidationError(MANIFEST, itemPath, REASON_REFERENCE_INVALID,
                        "model references must be non-empty strings"));
                continue;
            }
            if (!seen.add(item.getAsString())) {
                errors.add(new ValidationError(MANIFEST, itemPath, REASON_DUPLICATE_REFERENCE,
                        "duplicate model reference"));
            }
        }
    }

    private void validateEnvironmentRef(JsonObject manifest, List<ValidationError> errors) {
        if (!manifest.has("environment")) {
            return;
        }
        JsonElement element = manifest.get("environment");
        if (!isNonBlankString(element)) {
            errors.add(new ValidationError(MANIFEST, "$.environment", REASON_TYPE_MISMATCH,
                    "environment must be a non-empty string"));
            return;
        }
        if (!"environment.json".equals(element.getAsString())) {
            errors.add(new ValidationError(MANIFEST, "$.environment", REASON_REFERENCE_INVALID,
                    "environment template must be environment.json"));
        }
    }

    private void validateRuntimeProfile(JsonObject manifest, List<ValidationError> errors) {
        if (!manifest.has("runtimeProfile")) {
            errors.add(new ValidationError(MANIFEST, "$.runtimeProfile", REASON_MISSING_REQUIRED_KEY,
                    "required key is missing: runtimeProfile"));
            return;
        }
        JsonElement element = manifest.get("runtimeProfile");
        if (!isNonBlankString(element)) {
            errors.add(new ValidationError(MANIFEST, "$.runtimeProfile", REASON_TYPE_MISMATCH,
                    "runtimeProfile must be a string"));
            return;
        }
        String profile = element.getAsString();
        if (DENIED_PROFILE.equals(profile)) {
            errors.add(new ValidationError(MANIFEST, "$.runtimeProfile", REASON_PROFILE_DENIED,
                    "runtimeProfile is denied and cannot be selected from a project file"));
            return;
        }
        if (!SUPPORTED_RUNTIME_PROFILES.contains(profile)) {
            errors.add(new ValidationError(MANIFEST, "$.runtimeProfile", REASON_PROFILE_UNSUPPORTED,
                    "runtimeProfile is not supported: " + cap(profile)));
        }
    }

    private void validateBooleanPolicyObject(JsonObject manifest, String key, Set<String> allowedKeys,
                                             List<ValidationError> errors) {
        if (!manifest.has(key)) {
            return;
        }
        JsonElement element = manifest.get(key);
        String jsonPath = "$." + key;
        if (!element.isJsonObject()) {
            errors.add(new ValidationError(MANIFEST, jsonPath, REASON_TYPE_MISMATCH,
                    key + " must be an object"));
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        checkKeys(obj, allowedKeys, jsonPath, MANIFEST, errors);
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (!isBoolean(entry.getValue())) {
                errors.add(new ValidationError(MANIFEST, jsonPath + "." + entry.getKey(),
                        REASON_TYPE_MISMATCH, entry.getKey() + " must be a boolean"));
            }
        }
    }

    private void validateCapabilities(JsonObject manifest, List<ValidationError> errors) {
        if (!manifest.has("capabilities")) {
            return;
        }
        JsonElement element = manifest.get("capabilities");
        if (!element.isJsonObject()) {
            errors.add(new ValidationError(MANIFEST, "$.capabilities", REASON_TYPE_MISMATCH,
                    "capabilities must be an object"));
            return;
        }
        JsonObject caps = element.getAsJsonObject();
        checkKeys(caps, CAPABILITY_POLICY_KEYS, "$.capabilities", MANIFEST, errors);
        for (Map.Entry<String, JsonElement> entry : caps.entrySet()) {
            String key = entry.getKey();
            String jsonPath = "$.capabilities." + key;
            if (HARD_DENIED_CAPABILITIES.contains(key)) {
                if (!isBoolean(entry.getValue())) {
                    errors.add(new ValidationError(MANIFEST, jsonPath, REASON_TYPE_MISMATCH,
                            key + " must be a boolean"));
                    continue;
                }
                if (entry.getValue().getAsBoolean()) {
                    errors.add(new ValidationError(MANIFEST, jsonPath, REASON_CAPABILITY_ABOVE_CEILING,
                            "capability is above the platform ceiling and cannot be enabled "
                                    + "by project config: " + key));
                }
            } else {
                validateStringArray(caps, key, jsonPath, MANIFEST, errors);
            }
        }
    }

    private void validateStringArray(JsonObject obj, String key, String jsonPath, String filePath,
                                     List<ValidationError> errors) {
        if (!obj.has(key)) {
            return;
        }
        JsonElement element = obj.get(key);
        if (!element.isJsonArray()) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_TYPE_MISMATCH,
                    key + " must be an array of strings"));
            return;
        }
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            if (!isNonBlankString(array.get(i))) {
                errors.add(new ValidationError(filePath, jsonPath + "[" + i + "]", REASON_TYPE_MISMATCH,
                        key + " entries must be non-empty strings"));
            }
        }
    }

    private void validateNamesArray(JsonObject obj, String key, String jsonPath, String filePath,
                                    List<ValidationError> errors) {
        if (!obj.has(key)) {
            return;
        }
        JsonElement element = obj.get(key);
        if (!element.isJsonArray()) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_TYPE_MISMATCH,
                    key + " must be an array of names"));
            return;
        }
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            String itemPath = jsonPath + "[" + i + "]";
            if (!isNonBlankString(item)) {
                errors.add(new ValidationError(filePath, itemPath, REASON_TYPE_MISMATCH,
                        key + " entries must be non-empty strings"));
                continue;
            }
            if (item.getAsString().contains("=")) {
                errors.add(new ValidationError(filePath, itemPath, REASON_SECRET_VALUE,
                        "values must not be inlined; declare environment names only"));
            }
        }
    }

    private void validateEnum(JsonObject obj, String key, String jsonPath, String filePath,
                              Set<String> allowed, List<ValidationError> errors) {
        if (!obj.has(key)) {
            return;
        }
        JsonElement element = obj.get(key);
        if (!isNonBlankString(element)) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_TYPE_MISMATCH,
                    key + " must be a string"));
            return;
        }
        if (!allowed.contains(element.getAsString())) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_INVALID_ENUM_VALUE,
                    key + " has an unsupported value"));
        }
    }

    private void validateNonNegativeNumber(JsonObject obj, String key, String jsonPath, String filePath,
                                           List<ValidationError> errors) {
        if (!obj.has(key)) {
            return;
        }
        JsonElement element = obj.get(key);
        if (!isNumber(element)) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_TYPE_MISMATCH,
                    key + " must be a number"));
            return;
        }
        if (element.getAsDouble() < 0) {
            errors.add(new ValidationError(filePath, jsonPath, REASON_INVALID_VALUE,
                    key + " must not be negative"));
        }
    }

    private void checkKeys(JsonObject obj, Set<String> allowedKeys, String jsonPath, String filePath,
                           List<ValidationError> errors) {
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = entry.getKey();
            if (allowedKeys.contains(key)) {
                continue;
            }
            String keyPath = jsonPath + "." + key;
            if (SECRET_KEY_PATTERN.matcher(key).matches()) {
                errors.add(new ValidationError(filePath, keyPath, REASON_SECRET_KEY,
                        "secret-looking configuration key is not allowed; remove it"));
            } else {
                errors.add(new ValidationError(filePath, keyPath, REASON_UNKNOWN_KEY,
                        "unknown configuration key: " + cap(key)));
            }
        }
    }

    private void checkDuplicateDefinition(Map<String, String> firstDefinition, String semanticKey,
                                          LoadedChild child, String label, List<ValidationError> errors) {
        String firstRef = firstDefinition.putIfAbsent(semanticKey, child.refPath());
        if (firstRef != null) {
            errors.add(new ValidationError(child.refPath(), "$", REASON_DUPLICATE_DEFINITION,
                    "duplicate " + label + " already defined in " + firstRef));
        }
    }

    private void validateRefFormat(LoadedChild child, List<ValidationError> errors) {
        String ref = child.refPath();
        if (ref == null || ref.isBlank()) {
            errors.add(new ValidationError(String.valueOf(ref), "", REASON_REFERENCE_INVALID,
                    "child file reference must not be blank"));
            return;
        }
        String normalized = ref.replace('\\', '/');
        if (normalized.contains("..") || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            errors.add(new ValidationError(ref, "", REASON_REFERENCE_INVALID,
                    "child file reference must be a relative path inside the project config directory"));
            return;
        }
        switch (child.kind()) {
            case AGENT -> {
                if (!normalized.startsWith("agents/") || !normalized.endsWith(".json")) {
                    errors.add(new ValidationError(ref, "", REASON_REFERENCE_INVALID,
                            "agent definitions must live under agents/*.json"));
                }
            }
            case TOOL -> {
                if (!normalized.startsWith("tools/") || !normalized.endsWith(".json")) {
                    errors.add(new ValidationError(ref, "", REASON_REFERENCE_INVALID,
                            "tool definitions must live under tools/*.json"));
                }
            }
            case MCP -> {
                if (!normalized.startsWith("mcp/") || !normalized.endsWith(".json")) {
                    errors.add(new ValidationError(ref, "", REASON_REFERENCE_INVALID,
                            "MCP definitions must live under mcp/*.json"));
                }
            }
            case SKILL -> {
                if (!normalized.startsWith("skills/") || !normalized.endsWith(".md")) {
                    errors.add(new ValidationError(ref, "", REASON_REFERENCE_INVALID,
                            "skills must live under skills/*.md"));
                }
            }
            case ENVIRONMENT -> {
                if (!"environment.json".equals(normalized)) {
                    errors.add(new ValidationError(ref, "", REASON_REFERENCE_INVALID,
                            "environment template must be environment.json"));
                }
            }
            default -> errors.add(new ValidationError(ref, "", REASON_REFERENCE_INVALID,
                    "unsupported child document kind"));
        }
    }

    private String skillSlug(String refPath) {
        String normalized = refPath.replace('\\', '/');
        if (!normalized.startsWith("skills/") || !normalized.endsWith(".md")) {
            return null;
        }
        String name = normalized.substring("skills/".length(), normalized.length() - ".md".length());
        int lastSlash = name.lastIndexOf('/');
        return lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
    }

    private Set<String> collectToolNames(List<LoadedChild> children) {
        Set<String> names = new HashSet<>();
        for (LoadedChild child : children) {
            if (child.kind() == ChildKind.TOOL && child.json() != null
                    && hasNonBlankString(child.json(), "name")) {
                names.add(child.json().get("name").getAsString());
            }
        }
        return names;
    }

    private Set<String> collectModelRefs(JsonObject manifest) {
        Set<String> refs = new HashSet<>();
        if (manifest != null && manifest.has("models") && manifest.get("models").isJsonArray()) {
            for (JsonElement element : manifest.getAsJsonArray("models")) {
                if (isNonBlankString(element)) {
                    refs.add(element.getAsString());
                }
            }
        }
        return refs;
    }

    private static boolean containsString(JsonArray array, String value) {
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    && value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonBlankString(JsonObject obj, String key) {
        return obj.has(key) && isNonBlankString(obj.get(key));
    }

    private static boolean isNonBlankString(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                && !element.getAsString().isBlank();
    }

    private static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber();
    }

    private static boolean isBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean();
    }

    private static String cap(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= 80 ? sanitized : sanitized.substring(0, 80);
    }
}
