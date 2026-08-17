package com.labex.labexagent.projectconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService.EnvironmentStatus;
import com.labex.labexagent.projectconfig.AgentProjectConfigRevisionService.ProjectConfigView;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ValidationError;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves the complete non-secret effective project configuration for a task run.
 *
 * <p>The input is the validated project configuration package (manifest + declared children)
 * plus the request defaults (model selection and mode); the output is one canonical,
 * non-secret effective document with stable fingerprints that {@code t_agent_run_config_snapshot}
 * persists before the task enters {@code queued}. Catalog resources (MCP/Skill/named Agent/tool)
 * are referenced by their declared identifiers and digests here; the epoch-scoped runtime
 * resolution of those catalogs is owned by the capability phase (Tasks 3.x).
 *
 * <p>Fail-closed semantics: an invalid configuration tree or a pending external change blocks
 * task queueing with a typed {@link EffectiveConfigException}; no default fallback is ever used.
 * A project WITHOUT a configuration package (missing {@code agent.json}) is a valid default
 * state: the effective document falls back to request defaults with the platform-default strict
 * profile, so projects that never opted into project-level configuration keep working.
 */
@Component
public class AgentEffectiveProjectConfigService {

    /** Canonical digest of an absent optional policy node. */
    private static final String EMPTY_OBJECT_DIGEST =
            AgentProjectConfigCanonicalizer.sha256Hex("{}");

    /** Key-based masking (same contract as the revision service's redacted view). */
    private static final Pattern REDACTED_KEY = Pattern.compile(
            "(?i).*(api[_-]?key|access[_-]?key|secret|token|password|passwd|authorization|"
                    + "auth[_-]?header|private[_-]?key|refresh[_-]?token|pwd).*");

    /** Value-shaped secrets that must never leave the snapshot even under an allowed key. */
    private static final Pattern SECRET_SHAPED_VALUE = Pattern.compile(
            "(?i)(sk-|Bearer\\s+|ghp_|xoxb-|AKIA[0-9A-Z]{16}|-----BEGIN|\\w+=.+)");

    private static final String MASK = "***";

    private final AgentProjectConfigRevisionService revisionService;

    public AgentEffectiveProjectConfigService(AgentProjectConfigRevisionService revisionService) {
        this.revisionService = revisionService;
    }

    /**
     * Immutable effective configuration projection persisted into the run snapshot.
     *
     * @param projectConfigRevision  accepted revision this document is based on
     * @param projectConfigDigest    accepted canonical config digest
     * @param effectiveConfigJson    canonical non-secret effective document JSON
     * @param effectiveConfigDigest  SHA-256 of {@code effectiveConfigJson}
     * @param modelFingerprint       digest of the frozen model selection plus mode
     * @param capabilityDigest       canonical digest of the manifest capability policy
     * @param resourceDigest         canonical tree digest of the declared resource files
     * @param runtimeProfile         selected worker profile
     * @param networkPolicyJson      canonical network policy node
     * @param verificationPolicyJson canonical verification policy node
     * @param secretAliasesJson      environment variable NAMES only (never values)
     */
    public record EffectiveProjectConfig(
            Long projectConfigRevision,
            String projectConfigDigest,
            String effectiveConfigJson,
            String effectiveConfigDigest,
            String modelFingerprint,
            String capabilityDigest,
            String resourceDigest,
            String runtimeProfile,
            String networkPolicyJson,
            String verificationPolicyJson,
            String secretAliasesJson) {
    }

    /** Typed, redaction-safe failure when the project configuration blocks task queueing. */
    public static final class EffectiveConfigException extends RuntimeException {
        private final String reasonCode;
        private final List<ValidationError> errors;

        public EffectiveConfigException(String reasonCode, List<ValidationError> errors) {
            super("Effective project configuration is not available: " + reasonCode);
            this.reasonCode = reasonCode;
            this.errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public String reasonCode() {
            return reasonCode;
        }

        public List<ValidationError> errors() {
            return errors;
        }
    }

    /**
     * Resolves the effective document for an owned project. Ownership must be established by the
     * caller (see {@link AgentProjectConfigOwnership}); the revision service enforces it again.
     *
     * @param modelConfigId the exact frozen model selection persisted on the task (may be null
     *                      only when the caller guarantees the task already carries it)
     * @param mode          the durable task mode
     */
    public EffectiveProjectConfig resolve(Integer studentId, StudentProject project,
                                          Integer modelConfigId, String mode) {
        ProjectConfigView view = revisionService.load(studentId, project.getProjectId());
        if (!view.valid()) {
            // 项目级配置是可选的：`.labex-agent/project/agent.json` 不存在（PATH_MISSING 且路径恰为
            // manifest）时，使用平台默认的"无项目配置"effective 文档；配置包存在但非法时仍然
            // fail closed，绝不静默忽略非法配置。
            if (isConfigPackageAbsent(view)) {
                return resolveDefaults(project, modelConfigId, mode);
            }
            throw new EffectiveConfigException("config-invalid", view.errors());
        }
        if (view.externalChangePending()) {
            throw new EffectiveConfigException("external-change-pending", List.of());
        }

        AgentProjectConfigDocument document = readDocument(Path.of(project.getWorkspacePath()));
        // 第二次读盘后必须与 accepted revision 的 canonical digest 对齐：任何不一致（外部编辑
        // 恰好发生在两次读取之间）都按 pending external change fail closed，快照绝不绑定
        // 未经验证的文档版本。
        if (!document.sha256Digest().equals(view.configDigest())) {
            throw new EffectiveConfigException("external-change-pending", List.of());
        }
        JsonObject effective = buildEffectiveDocument(document, modelConfigId, mode, view);
        String effectiveConfigJson = AgentProjectConfigCanonicalizer.canonicalJson(effective);
        String effectiveConfigDigest = AgentProjectConfigCanonicalizer.sha256Hex(effectiveConfigJson);
        String modelFingerprint = AgentProjectConfigCanonicalizer.sha256Hex(
                (modelConfigId == null ? "null" : modelConfigId) + "|" + (mode == null ? "" : mode));
        String capabilityDigest = nodeDigest(document.manifest(), "capabilities");
        String networkPolicyJson = nodeJson(document.manifest(), "networkPolicy");
        String verificationPolicyJson = nodeJson(document.manifest(), "verificationPolicy");
        String secretAliasesJson = secretAliases(document);
        return new EffectiveProjectConfig(
                view.revision(), view.configDigest(),
                effectiveConfigJson, effectiveConfigDigest,
                modelFingerprint, capabilityDigest,
                view.treeDigest() == null ? EMPTY_OBJECT_DIGEST : view.treeDigest(),
                view.runtimeProfile(),
                networkPolicyJson, verificationPolicyJson,
                secretAliasesJson);
    }

    /**
     * A project has NO configuration package when the only load failure is the missing manifest:
     * exactly one error for {@code agent.json} with {@code PATH_MISSING}. Any other error shape
     * (present-but-invalid, symlink, traversal, scan failure) stays fail-closed.
     */
    private boolean isConfigPackageAbsent(ProjectConfigView view) {
        List<ValidationError> errors = view.errors();
        return errors != null && errors.size() == 1
                && AgentProjectConfigValidator.MANIFEST.equals(errors.get(0).filePath())
                && AgentProjectConfigValidator.REASON_PATH_MISSING.equals(errors.get(0).reasonCode());
    }

    /**
     * Platform-default effective document for projects without a configuration package: request
     * defaults only, strict runtime profile, empty policies and no accepted revision. The digest
     * is stable for identical inputs; a later-added configuration package produces a different
     * digest and a new snapshot on the next epoch.
     */
    private EffectiveProjectConfig resolveDefaults(StudentProject project,
                                                   Integer modelConfigId, String mode) {
        JsonObject effective = new JsonObject();
        JsonObject defaults = new JsonObject();
        defaults.addProperty("modelConfigId", modelConfigId);
        defaults.addProperty("mode", mode);
        effective.add("defaults", defaults);
        JsonObject enabledResources = new JsonObject();
        for (String key : List.of("agents", "tools", "mcpServers", "skills", "models")) {
            enabledResources.add(key, new com.google.gson.JsonArray());
        }
        effective.add("enabledResources", enabledResources);
        effective.addProperty("runtimeProfile", "strict");
        effective.addProperty("trustStatus", "untrusted");
        String effectiveConfigJson = AgentProjectConfigCanonicalizer.canonicalJson(effective);
        String modelFingerprint = AgentProjectConfigCanonicalizer.sha256Hex(
                (modelConfigId == null ? "null" : modelConfigId) + "|" + (mode == null ? "" : mode));
        return new EffectiveProjectConfig(
                null, null,
                effectiveConfigJson,
                AgentProjectConfigCanonicalizer.sha256Hex(effectiveConfigJson),
                modelFingerprint, EMPTY_OBJECT_DIGEST, EMPTY_OBJECT_DIGEST,
                "strict", "{}", "{}", "[]");
    }

    private AgentProjectConfigDocument readDocument(Path projectRoot) {
        try {
            return new AgentProjectConfigReader().read(projectRoot);
        } catch (AgentProjectConfigReader.ConfigReadException e) {
            throw new EffectiveConfigException("config-invalid", e.errors());
        }
    }

    private JsonObject buildEffectiveDocument(AgentProjectConfigDocument document,
                                              Integer modelConfigId, String mode,
                                              ProjectConfigView view) {
        JsonObject effective = new JsonObject();
        effective.addProperty("schemaVersion", document.schemaVersion());
        // 快照只保存脱敏投影：键名命中秘密形态或数组值命中秘密形态的字符串一律掩码，
        // 布尔/数字保留类型；第一道防线仍是 validator 拒绝秘密键与内联秘密值。
        effective.add("manifest", redactJson(document.manifest()));
        JsonObject children = new JsonObject();
        document.childDocuments().forEach((name, child) -> children.add(name, redactJson(child)));
        document.childTexts().forEach(children::addProperty);
        effective.add("children", children);
        JsonObject defaults = new JsonObject();
        defaults.addProperty("modelConfigId", modelConfigId);
        defaults.addProperty("mode", mode);
        effective.add("defaults", defaults);
        JsonObject enabledResources = new JsonObject();
        view.enabledResources().forEach((key, values) -> {
            var array = new com.google.gson.JsonArray();
            values.forEach(array::add);
            enabledResources.add(key, array);
        });
        effective.add("enabledResources", enabledResources);
        effective.addProperty("runtimeProfile", view.runtimeProfile());
        effective.addProperty("trustStatus", view.trustStatus());
        return effective;
    }

    private String nodeDigest(JsonObject manifest, String key) {
        if (!manifest.has(key)) {
            return EMPTY_OBJECT_DIGEST;
        }
        JsonElement node = manifest.get(key);
        if (!node.isJsonObject()) {
            return EMPTY_OBJECT_DIGEST;
        }
        return AgentProjectConfigCanonicalizer.sha256Hex(
                AgentProjectConfigCanonicalizer.canonicalJson(node));
    }

    /**
     * Deep-copies the object, masking secret-shaped content. Key-based masking applies only to
     * STRING primitives (booleans and numbers keep their type); string elements inside arrays
     * are masked when their VALUE matches a secret-shaped pattern, so raw secrets smuggled
     * under allowed keys (for example {@code "args": ["--api-key", "sk-..."]}) never leave
     * the snapshot.
     */
    private JsonObject redactJson(JsonObject input) {
        JsonObject copy = input.deepCopy();
        redactInto(copy);
        return copy;
    }

    private void redactInto(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                redactInto(value.getAsJsonObject());
            } else if (value.isJsonArray()) {
                redactArray(value.getAsJsonArray());
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                    && REDACTED_KEY.matcher(entry.getKey()).matches()) {
                entry.setValue(new JsonPrimitive(MASK));
            }
        }
    }

    private void redactArray(JsonArray array) {
        for (int index = 0; index < array.size(); index++) {
            JsonElement item = array.get(index);
            if (item.isJsonObject()) {
                redactInto(item.getAsJsonObject());
            } else if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()
                    && SECRET_SHAPED_VALUE.matcher(item.getAsString()).find()) {
                array.set(index, new JsonPrimitive(MASK));
            }
        }
    }

    private String nodeJson(JsonObject manifest, String key) {
        if (!manifest.has(key)) {
            return "{}";
        }
        JsonElement node = manifest.get(key);
        if (!node.isJsonObject()) {
            return "{}";
        }
        return AgentProjectConfigCanonicalizer.canonicalJson(node);
    }

    /** Environment template variable NAMES only; values are never part of the snapshot. */
    private String secretAliases(AgentProjectConfigDocument document) {
        JsonObject environment = document.childDocuments().get("environment.json");
        com.google.gson.JsonArray aliases = new com.google.gson.JsonArray();
        if (environment != null && environment.has("variables")
                && environment.get("variables").isJsonArray()) {
            for (JsonElement element : environment.getAsJsonArray("variables")) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    aliases.add(element.getAsString());
                }
            }
        }
        return AgentProjectConfigCanonicalizer.canonicalJson(aliases);
    }
}
