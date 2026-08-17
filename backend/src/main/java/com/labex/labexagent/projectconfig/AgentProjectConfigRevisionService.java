package com.labex.labexagent.projectconfig;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.labex.entity.AgentProjectConfigRevision;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigReader.ConfigReadException;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ValidationError;
import com.labex.labexagent.projectconfig.ProtectedProjectConfigPath.PathViolationException;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Revision authority for the Git-visible project configuration package
 * ({@code .labex-agent/project/**}).
 *
 * <p>Every load requires {@link AgentProjectConfigOwnership#requireOwned} first. On the first
 * load of a valid tree it persists revision one with the canonical document digest, a
 * canonical tree digest and audit metadata. On subsequent loads it compares the current
 * canonical tree digest against the accepted revision's tree reference; a formatting-only
 * difference keeps the same canonical digest and changes nothing, while a semantic or
 * structural difference is reported through {@link AgentProjectConfigExternalChangeService}
 * as an {@code external_change_pending} observation. The accepted revision is never
 * silently replaced and the proposal table is never written from this reader.
 *
 * <p>Malformed, missing, oversized or link-violating trees fail closed with structured,
 * redaction-safe {@link ValidationError}s and no new revision.
 */
@Component
public class AgentProjectConfigRevisionService {

    /** First accepted revision created for a project. */
    public static final long INITIAL_REVISION = 1L;

    public static final String VALIDATION_VALID = "valid";
    public static final String VALIDATION_INVALID = "invalid";
    public static final String TREE_REFERENCE_PREFIX = "tree:";

    private static final Pattern REDACTED_KEY = Pattern.compile(
            "(?i).*(api[_-]?key|access[_-]?key|secret|token|password|passwd|authorization|"
                    + "auth[_-]?header|private[_-]?key|refresh[_-]?token|pwd).*");

    /** Sentinel replacing any redacted value; never a real configuration value. */
    private static final String MASK = "***";

    /** Value-shaped secrets that must never leave the view even under an allowed key. */
    private static final Pattern SECRET_SHAPED_VALUE = Pattern.compile(
            "(?i)(sk-|Bearer\\s+|ghp_|xoxb-|AKIA[0-9A-Z]{16}|-----BEGIN|\\w+=.+)");

    /** Durable external-change observation surfaced to callers; it is never a decision. */
    public record ExternalChangeInfo(Long id, Long baseRevision, String observedTreeDigest,
                                     List<String> changedPaths, LocalDateTime detectedAt) {
    }

    /** Redacted environment template summary. */
    public record EnvironmentStatus(boolean configured, List<String> variables, String installPolicy) {
    }

    /** Immutable read projection of one project configuration load. */
    public record ProjectConfigView(boolean valid, String validationStatus, Long revision,
                                    String configDigest, String treeDigest,
                                    boolean externalChangePending, ExternalChangeInfo externalChange,
                                    Map<String, Object> redactedManifest,
                                    Map<String, Map<String, Object>> redactedChildren,
                                    Map<String, List<String>> enabledResources,
                                    EnvironmentStatus environmentStatus,
                                    String runtimeProfile, String trustStatus,
                                    List<ValidationError> errors) {
    }

    private final StudentProjectService studentProjectService;
    private final AgentProjectConfigRevisionMapper revisionMapper;
    private final AgentProjectConfigExternalChangeService externalChangeService;
    private final AgentProjectConfigReader reader;

    public AgentProjectConfigRevisionService(StudentProjectService studentProjectService,
                                             AgentProjectConfigRevisionMapper revisionMapper,
                                             AgentProjectConfigExternalChangeService externalChangeService) {
        this.studentProjectService = studentProjectService;
        this.revisionMapper = revisionMapper;
        this.externalChangeService = externalChangeService;
        this.reader = new AgentProjectConfigReader();
    }

    /**
     * Loads the owned project's configuration, validating the tree, creating revision one on
     * first load, and detecting external edits without silently creating a new revision.
     */
    public ProjectConfigView load(Integer studentId, Integer projectId) {
        StudentProject project = AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        Path projectRoot = Path.of(project.getWorkspacePath());
        AgentProjectConfigRevision accepted = latestRevision(projectId);

        AgentProjectConfigDocument document;
        try {
            document = reader.read(projectRoot);
        } catch (ConfigReadException e) {
            return invalidView(accepted, e.errors());
        }

        TreeScan scan = scanTree(projectRoot, document);
        if (scan.errors != null) {
            return invalidView(accepted, scan.errors);
        }

        if (accepted == null) {
            AgentProjectConfigRevision created = insertInitialRevision(project, document, scan);
            return validView(created, document, scan, null);
        }

        String acceptedTreeDigest = parseTreeReference(accepted.getTreeReference());
        if (acceptedTreeDigest != null && acceptedTreeDigest.equals(scan.treeDigest)) {
            return validView(accepted, document, scan, null);
        }
        if (acceptedTreeDigest == null && Objects.equals(accepted.getConfigDigest(), document.sha256Digest())) {
            return validView(accepted, document, scan, null);
        }

        List<String> changedPaths = changedPaths(acceptedFiles(accepted), scan.fileHashes);
        AgentProjectConfigExternalChangeService.PendingExternalChange pending =
                externalChangeService.recordPending(studentId, projectId, accepted.getRevision(),
                        scan.treeDigest, changedPaths);
        ExternalChangeInfo externalChange = new ExternalChangeInfo(
                pending.id(), pending.baseRevision(), pending.observedTreeDigest(),
                changedPaths, pending.detectedAt());
        return validView(accepted, document, scan, externalChange);
    }

    private AgentProjectConfigRevision latestRevision(Integer projectId) {
        return revisionMapper.selectOne(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", projectId)
                .orderByDesc("revision")
                .last("LIMIT 1"));
    }

    private AgentProjectConfigRevision insertInitialRevision(StudentProject project,
                                                             AgentProjectConfigDocument document,
                                                             TreeScan scan) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("document", document.canonicalJson());
        envelope.addProperty("treeDigest", scan.treeDigest);
        JsonObject files = new JsonObject();
        scan.fileHashes.forEach(files::addProperty);
        envelope.add("files", files);

        AgentProjectConfigRevision revision = new AgentProjectConfigRevision();
        revision.setStudentId(project.getStudentId());
        revision.setProjectId(project.getProjectId());
        revision.setRevision(INITIAL_REVISION);
        revision.setConfigDigest(document.sha256Digest());
        revision.setTreeReference(TREE_REFERENCE_PREFIX + scan.treeDigest);
        revision.setSchemaVersion(document.schemaVersion());
        revision.setNormalizedConfig(envelope.toString());
        revision.setValidationStatus(VALIDATION_VALID);
        revision.setSourceActor("student:" + project.getStudentId());
        try {
            revisionMapper.insert(revision);
            return revision;
        } catch (Exception raced) {
            // Any insert failure is intentionally treated as a potential concurrent first-load
            // race: re-check the project's latest row for the unique (project_id, revision) key
            // before failing. This catch must never widen into other recovery behavior.
            AgentProjectConfigRevision existing = latestRevision(project.getProjectId());
            if (existing != null) {
                return existing;
            }
            throw raced;
        }
    }

    private ProjectConfigView validView(AgentProjectConfigRevision accepted, AgentProjectConfigDocument document,
                                        TreeScan scan, ExternalChangeInfo externalChange) {
        Map<String, Object> redactedManifest = toPlain(redact(document.manifest()));
        Map<String, Map<String, Object>> redactedChildren = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> child : document.childDocuments().entrySet()) {
            redactedChildren.put(child.getKey(), toPlain(redact(child.getValue())));
        }
        return new ProjectConfigView(true, VALIDATION_VALID, accepted.getRevision(),
                accepted.getConfigDigest(), scan.treeDigest,
                externalChange != null, externalChange,
                redactedManifest, redactedChildren,
                enabledResources(document),
                environmentStatus(document),
                runtimeProfile(document),
                trustStatus(runtimeProfile(document)),
                List.of());
    }

    private ProjectConfigView invalidView(AgentProjectConfigRevision accepted, List<ValidationError> errors) {
        return new ProjectConfigView(false, VALIDATION_INVALID,
                accepted == null ? null : accepted.getRevision(),
                accepted == null ? null : accepted.getConfigDigest(),
                null, false, null,
                null, Map.of(), Map.of(),
                new EnvironmentStatus(false, List.of(), null),
                null, "unknown", List.copyOf(errors));
    }

    private Map<String, List<String>> enabledResources(AgentProjectConfigDocument document) {
        JsonObject manifest = document.manifest();
        Map<String, List<String>> resources = new LinkedHashMap<>();
        for (String key : List.of("agents", "tools", "mcpServers", "skills", "models")) {
            resources.put(key, stringArray(manifest, key));
        }
        return Map.copyOf(resources);
    }

    private EnvironmentStatus environmentStatus(AgentProjectConfigDocument document) {
        JsonObject environment = document.childDocuments().get("environment.json");
        if (environment == null) {
            return new EnvironmentStatus(false, List.of(), null);
        }
        List<String> variables = stringArray(environment, "variables").stream()
                .map(this::maskSecretShaped)
                .toList();
        return new EnvironmentStatus(true, variables,
                environment.has("installPolicy") ? environment.get("installPolicy").getAsString() : null);
    }

    private String runtimeProfile(AgentProjectConfigDocument document) {
        JsonObject manifest = document.manifest();
        if (manifest.has("runtimeProfile") && manifest.get("runtimeProfile").isJsonPrimitive()) {
            return manifest.get("runtimeProfile").getAsString();
        }
        return null;
    }

    private String trustStatus(String runtimeProfile) {
        if (runtimeProfile == null) {
            return "unknown";
        }
        return switch (runtimeProfile) {
            case "strict" -> "untrusted";
            case "integrated-wsl", "integrated-windows" -> "trusted";
            case "full-access" -> "high-risk";
            default -> "unknown";
        };
    }

    private List<String> stringArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }
        JsonArray array = object.getAsJsonArray(key);
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                values.add(element.getAsString());
            }
        }
        return List.copyOf(values);
    }

    /**
     * Deep-copies the object, masking secret-shaped content. Key-based masking applies only to
     * STRING primitives (booleans and numbers keep their type); string elements inside arrays
     * are masked when their VALUE matches a secret-shaped pattern, so raw secrets smuggled
     * under allowed keys (for example {@code "args": ["--api-key", "sk-..."]}) never leak.
     */
    private JsonObject redact(JsonObject input) {
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

    /** Masks secret-shaped values while preserving the shape of allowed non-secret content. */
    private String maskSecretShaped(String value) {
        return SECRET_SHAPED_VALUE.matcher(value).find() ? MASK : value;
    }

    private Map<String, Object> toPlain(JsonObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            result.put(entry.getKey(), toPlain(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private Object toPlain(JsonElement element) {
        if (element.isJsonObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                result.put(entry.getKey(), toPlain(entry.getValue()));
            }
            return result;
        }
        if (element.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                result.add(toPlain(item));
            }
            return result;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            return primitive.getAsString();
        }
        return null;
    }

    private String parseTreeReference(String treeReference) {
        if (treeReference == null || !treeReference.startsWith(TREE_REFERENCE_PREFIX)) {
            return null;
        }
        return treeReference.substring(TREE_REFERENCE_PREFIX.length());
    }

    private Map<String, String> acceptedFiles(AgentProjectConfigRevision accepted) {
        if (accepted.getNormalizedConfig() == null) {
            return Map.of();
        }
        try {
            JsonObject envelope = JsonParser.parseString(accepted.getNormalizedConfig()).getAsJsonObject();
            if (!envelope.has("files") || !envelope.get("files").isJsonObject()) {
                return Map.of();
            }
            Map<String, String> files = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : envelope.getAsJsonObject("files").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    files.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return files;
        } catch (JsonSyntaxException | IllegalStateException e) {
            return Map.of();
        }
    }

    private List<String> changedPaths(Map<String, String> acceptedFiles, Map<String, String> currentFiles) {
        Set<String> paths = new TreeSet<>();
        Set<String> all = new TreeSet<>(acceptedFiles.keySet());
        all.addAll(currentFiles.keySet());
        for (String path : all) {
            if (!Objects.equals(acceptedFiles.get(path), currentFiles.get(path))) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    private TreeScan scanTree(Path projectRoot, AgentProjectConfigDocument document) {
        ProtectedProjectConfigPath boundary;
        try {
            boundary = new ProtectedProjectConfigPath(projectRoot);
        } catch (PathViolationException e) {
            return TreeScan.invalid(List.of(new ValidationError(
                    e.relativePath(), "", e.reasonCode(), e.getMessage())));
        }
        List<ValidationError> errors = new ArrayList<>();
        Map<String, String> fileHashes = new TreeMap<>();
        List<String> files = new ArrayList<>();
        walkTree(boundary.configDir(), "", boundary, document, files, fileHashes, errors);
        if (!errors.isEmpty()) {
            return TreeScan.invalid(errors);
        }
        StringBuilder builder = new StringBuilder();
        for (String file : files) {
            builder.append(file).append('\n');
            builder.append(fileHashes.get(file)).append('\n');
        }
        return new TreeScan(AgentProjectConfigCanonicalizer.sha256Hex(builder.toString()), fileHashes, null);
    }

    private void walkTree(Path dir, String prefix, ProtectedProjectConfigPath boundary,
                          AgentProjectConfigDocument document, List<String> files,
                          Map<String, String> fileHashes, List<ValidationError> errors) {
        try {
            BasicFileAttributes dirAttributes = Files.readAttributes(
                    dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (dirAttributes.isSymbolicLink() || dirAttributes.isOther()) {
                errors.add(new ValidationError(prefix.isEmpty() ? "." : prefix, "",
                        AgentProjectConfigValidator.REASON_PATH_SYMLINK,
                        "symbolic link or reparse point is not allowed"));
                return;
            }
            List<Path> entries;
            try (Stream<Path> stream = Files.list(dir)) {
                entries = stream.sorted().toList();
            }
            for (Path entry : entries) {
                BasicFileAttributes attributes = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String name = entry.getFileName().toString();
                String relative = prefix.isEmpty() ? name : prefix + "/" + name;
                if (attributes.isDirectory()) {
                    walkTree(entry, relative, boundary, document, files, fileHashes, errors);
                } else if (attributes.isRegularFile()) {
                    String canonical = canonicalContent(relative, boundary, document, errors);
                    if (canonical != null) {
                        files.add(relative);
                        fileHashes.put(relative, AgentProjectConfigCanonicalizer.sha256Hex(canonical));
                    }
                } else {
                    errors.add(new ValidationError(relative, "",
                            AgentProjectConfigValidator.REASON_PATH_SYMLINK,
                            "symbolic link or reparse point is not allowed"));
                }
            }
        } catch (IOException e) {
            errors.add(new ValidationError(prefix.isEmpty() ? "." : prefix, "",
                    AgentProjectConfigValidator.REASON_PATH_INVALID,
                    "protected config tree cannot be scanned"));
        }
    }

    private String canonicalContent(String relative, ProtectedProjectConfigPath boundary,
                                    AgentProjectConfigDocument document, List<ValidationError> errors) {
        try {
            String text = boundary.readText(relative);
            boolean jsonFile = AgentProjectConfigValidator.MANIFEST.equals(relative)
                    || document.childDocuments().containsKey(relative);
            if (jsonFile) {
                try {
                    JsonElement parsed = JsonParser.parseString(text);
                    if (!parsed.isJsonObject()) {
                        errors.add(new ValidationError(relative, "$",
                                AgentProjectConfigValidator.REASON_MALFORMED_JSON,
                                "config file is not valid JSON"));
                        return null;
                    }
                    return AgentProjectConfigCanonicalizer.canonicalJson(parsed);
                } catch (JsonSyntaxException e) {
                    errors.add(new ValidationError(relative, "$",
                            AgentProjectConfigValidator.REASON_MALFORMED_JSON,
                            "config file is not valid JSON"));
                    return null;
                }
            }
            return AgentProjectConfigCanonicalizer.normalizeLineEndings(text).strip();
        } catch (PathViolationException e) {
            errors.add(new ValidationError(e.relativePath(), "", e.reasonCode(), e.getMessage()));
            return null;
        }
    }

    private static final class TreeScan {
        private final String treeDigest;
        private final Map<String, String> fileHashes;
        private final List<ValidationError> errors;

        private TreeScan(String treeDigest, Map<String, String> fileHashes, List<ValidationError> errors) {
            this.treeDigest = treeDigest;
            this.fileHashes = fileHashes;
            this.errors = errors;
        }

        private static TreeScan invalid(List<ValidationError> errors) {
            return new TreeScan(null, Map.of(), List.copyOf(errors));
        }
    }
}
