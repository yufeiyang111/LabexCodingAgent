package com.labex.labexagent.projectconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.projectconfig.AgentProjectConfigReader.ConfigReadException;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ValidationError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentProjectConfigReaderTest {

    private static final String MANIFEST = """
        {
          "schemaVersion": 1,
          "defaultAgent": "agents/main.json",
          "agents": ["agents/main.json"],
          "tools": ["tools/build.json"],
          "mcpServers": ["mcp/typescript.json"],
          "skills": ["skills/backend.md"],
          "environment": "environment.json",
          "models": ["model-1"],
          "runtimeProfile": "strict",
          "networkPolicy": {"allowPublicDownloads": true, "allowLocalhost": true, "allowLan": false},
          "verificationPolicy": {"requireTests": true},
          "capabilities": {"enabledTools": ["read_file"], "deniedTools": []}
        }
        """;

    private static final String MANIFEST_FORMATTED = "{"
            + "\r\n  \"runtimeProfile\": \"strict\","
            + "\r\n  \"schemaVersion\": 1,"
            + "\r\n  \"capabilities\": { \"deniedTools\": [], \"enabledTools\": [\"read_file\"] },"
            + "\r\n  \"networkPolicy\": { \"allowLan\": false, \"allowLocalhost\": true, \"allowPublicDownloads\": true },"
            + "\r\n  \"verificationPolicy\": { \"requireTests\": true },"
            + "\r\n  \"models\": [\"model-1\"],"
            + "\r\n  \"environment\": \"environment.json\","
            + "\r\n  \"skills\": [\"skills/backend.md\"],"
            + "\r\n  \"mcpServers\": [\"mcp/typescript.json\"],"
            + "\r\n  \"tools\": [\"tools/build.json\"],"
            + "\r\n  \"agents\": [\"agents/main.json\"],"
            + "\r\n  \"defaultAgent\": \"agents/main.json\""
            + "\r\n}";

    private static final String AGENT_MAIN = "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be helpful\"}";
    private static final String TOOL_BUILD = "{\"name\":\"build\",\"script\":\"scripts/build.ps1\",\"description\":\"Build the project\"}";
    private static final String MCP_TYPESCRIPT = "{\"id\":\"ts\",\"command\":\"npx\",\"args\":[\"tsc\",\"--noEmit\"],\"credentialAlias\":\"ts-credential\"}";
    private static final String SKILL_BACKEND = "# Backend\r\n\r\nBuild and test instructions.\r\n";
    private static final String ENVIRONMENT = "{\"variables\":[\"NODE_ENV\"],\"installPolicy\":\"project-only\"}";

    @TempDir
    Path root;

    private Path configDir(Path project) {
        return project.resolve(".labex-agent").resolve("project");
    }

    private Path writeValidTree(String name) throws IOException {
        Path project = root.resolve(name);
        Path config = configDir(project);
        Files.createDirectories(config.resolve("agents"));
        Files.createDirectories(config.resolve("tools"));
        Files.createDirectories(config.resolve("mcp"));
        Files.createDirectories(config.resolve("skills"));
        Files.writeString(config.resolve("agent.json"), MANIFEST);
        Files.writeString(config.resolve("agents/main.json"), AGENT_MAIN);
        Files.writeString(config.resolve("tools/build.json"), TOOL_BUILD);
        Files.writeString(config.resolve("mcp/typescript.json"), MCP_TYPESCRIPT);
        Files.writeString(config.resolve("skills/backend.md"), SKILL_BACKEND);
        Files.writeString(config.resolve("environment.json"), ENVIRONMENT);
        return project;
    }

    private void replaceManifest(Path project, String manifest) throws IOException {
        Files.writeString(configDir(project).resolve("agent.json"), manifest);
    }

    private static boolean hasReason(ConfigReadException e, String reasonCode) {
        return e.errors().stream().anyMatch(err -> reasonCode.equals(err.reasonCode()));
    }

    private static boolean hasReasonAt(ConfigReadException e, String reasonCode, String filePath) {
        return e.errors().stream().anyMatch(err -> reasonCode.equals(err.reasonCode()) && filePath.equals(err.filePath()));
    }

    @Test
    void readsHappyPathManifestAndDeclaredChildren() throws Exception {
        Path project = writeValidTree("happy");
        AgentProjectConfigDocument doc = new AgentProjectConfigReader().read(project);

        assertEquals("1", doc.schemaVersion());
        assertEquals("strict", doc.manifest().get("runtimeProfile").getAsString());
        assertTrue(doc.sha256Digest().matches("[0-9a-f]{64}"));
        assertEquals(4, doc.childDocuments().size());
        assertTrue(doc.childDocuments().containsKey("agents/main.json"));
        assertTrue(doc.childDocuments().containsKey("tools/build.json"));
        assertTrue(doc.childDocuments().containsKey("mcp/typescript.json"));
        assertTrue(doc.childDocuments().containsKey("environment.json"));
        assertTrue(doc.childTexts().containsKey("skills/backend.md"));
        assertEquals("# Backend\n\nBuild and test instructions.", doc.childTexts().get("skills/backend.md"));
        assertEquals("main", doc.childDocuments().get("agents/main.json").get("id").getAsString());
    }

    @Test
    void ignoresUndeclaredChildFiles() throws Exception {
        Path project = writeValidTree("undeclared");
        String before = new AgentProjectConfigReader().read(project).sha256Digest();

        Files.writeString(configDir(project).resolve("agents/undeclared.json"),
                "{\"id\":\"ghost\",\"name\":\"Ghost Agent\"}");

        AgentProjectConfigDocument doc = new AgentProjectConfigReader().read(project);
        assertFalse(doc.childDocuments().containsKey("agents/undeclared.json"));
        assertEquals(before, doc.sha256Digest());
    }

    @Test
    void rejectsTraversalReferences() throws Exception {
        Path project = writeValidTree("traversal");
        replaceManifest(project, MANIFEST.replace("\"tools\": [\"tools/build.json\"]",
                "\"tools\": [\"tools/../../escape.json\"]"));

        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_PATH_TRAVERSAL));
    }

    @Test
    void rejectsAbsolutePathReferences() throws Exception {
        Path project = writeValidTree("absolute");
        String absoluteManifest = MANIFEST.replace("\"defaultAgent\": \"agents/main.json\"",
                "\"defaultAgent\": \"C:/evil.json\"");
        replaceManifest(project, absoluteManifest.replace("\"agents\": [\"agents/main.json\"]",
                "\"agents\": [\"C:/evil.json\"]"));

        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_PATH_ABSOLUTE));

        Path unixStyle = writeValidTree("absolute-unix");
        replaceManifest(unixStyle, MANIFEST.replace("\"mcpServers\": [\"mcp/typescript.json\"]",
                "\"mcpServers\": [\"/etc/evil.json\"]"));
        ConfigReadException unix = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader().read(unixStyle));
        assertTrue(hasReason(unix, AgentProjectConfigValidator.REASON_PATH_ABSOLUTE));
    }

    @Test
    void rejectsSymbolicLinkChildFiles() throws Exception {
        Path project = writeValidTree("symlink-child");
        Path outside = Files.createTempDirectory("labex-projectconfig-outside");
        Path secret = Files.writeString(outside.resolve("secret.json"), "{\"id\":\"evil\",\"name\":\"Evil\"}");
        Path link = configDir(project).resolve("agents").resolve("linked.json");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links are unavailable in this test environment");
        }
        replaceManifest(project, MANIFEST.replace("\"agents\": [\"agents/main.json\"]",
                "\"agents\": [\"agents/main.json\", \"agents/linked.json\"]"));

        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReasonAt(e, AgentProjectConfigValidator.REASON_PATH_SYMLINK, "agents/linked.json"));
    }

    @Test
    void rejectsConfigDirectoryThatEscapesTheProjectRoot() throws Exception {
        Path outside = Files.createTempDirectory("labex-projectconfig-outside");
        Path outsideConfig = Files.createDirectories(outside.resolve(".labex-agent").resolve("project"));
        Files.writeString(outsideConfig.resolve("agent.json"), MANIFEST);
        Path project = Files.createDirectories(root.resolve("linked-project"));
        Path link = project.resolve(".labex-agent");
        createDirectoryLinkOrAbort(link, outside.resolve(".labex-agent"));

        ProtectedProjectConfigPath.PathViolationException boundary = assertThrows(
                ProtectedProjectConfigPath.PathViolationException.class, () -> new ProtectedProjectConfigPath(project));
        assertEquals(AgentProjectConfigValidator.REASON_PATH_SYMLINK, boundary.reasonCode());
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_PATH_SYMLINK));
    }

    @Test
    void rejectsConfigDirectoryCreatedAsJunctionAfterBoundaryConstruction() throws Exception {
        Path project = Files.createDirectories(root.resolve("late-junction"));
        ProtectedProjectConfigPath boundary = new ProtectedProjectConfigPath(project);
        Path outside = Files.createTempDirectory("labex-projectconfig-outside");
        Path outsideConfig = Files.createDirectories(outside.resolve("config"));
        Files.writeString(outsideConfig.resolve("agent.json"), MANIFEST);
        Path labex = Files.createDirectories(project.resolve(".labex-agent"));
        createDirectoryLinkOrAbort(labex.resolve("project"), outsideConfig);

        ProtectedProjectConfigPath.PathViolationException e = assertThrows(
                ProtectedProjectConfigPath.PathViolationException.class,
                () -> boundary.readText("agent.json"));
        assertEquals(AgentProjectConfigValidator.REASON_PATH_SYMLINK, e.reasonCode());
    }

    @Test
    void rejectsIntermediateComponentAsJunction() throws Exception {
        Path project = root.resolve("junction-agents");
        Path config = configDir(project);
        Files.createDirectories(config.resolve("tools"));
        Files.createDirectories(config.resolve("mcp"));
        Files.createDirectories(config.resolve("skills"));
        Files.writeString(config.resolve("agent.json"), MANIFEST);
        Files.writeString(config.resolve("tools/build.json"), TOOL_BUILD);
        Files.writeString(config.resolve("mcp/typescript.json"), MCP_TYPESCRIPT);
        Files.writeString(config.resolve("skills/backend.md"), SKILL_BACKEND);
        Files.writeString(config.resolve("environment.json"), ENVIRONMENT);
        Path outside = Files.createTempDirectory("labex-projectconfig-outside");
        Path outsideAgents = Files.createDirectories(outside.resolve("agents"));
        Files.writeString(outsideAgents.resolve("main.json"), AGENT_MAIN);
        createDirectoryLinkOrAbort(config.resolve("agents"), outsideAgents);

        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReasonAt(e, AgentProjectConfigValidator.REASON_PATH_SYMLINK, "agents/main.json"));
    }

    @Test
    void acceptsBackslashSeparatedReferences() throws Exception {
        Path project = writeValidTree("backslash-ref");
        replaceManifest(project, MANIFEST.replace("\"tools\": [\"tools/build.json\"]",
                "\"tools\": [\"tools\\\\build.json\"]"));
        AgentProjectConfigDocument doc = new AgentProjectConfigReader().read(project);
        assertTrue(doc.childDocuments().containsKey("tools/build.json"));
    }

    @Test
    void rejectsDriveRelativePathReferences() throws Exception {
        Path project = writeValidTree("drive-relative");
        String manifest = MANIFEST.replace("\"defaultAgent\": \"agents/main.json\"",
                "\"defaultAgent\": \"C:evil.json\"");
        replaceManifest(project, manifest.replace("\"agents\": [\"agents/main.json\"]",
                "\"agents\": [\"C:evil.json\"]"));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_PATH_ABSOLUTE));
    }

    @Test
    void rejectsCrossArrayDuplicateReferences() throws Exception {
        Path project = writeValidTree("cross-array-dup");
        replaceManifest(project, MANIFEST.replace("\"agents\": [\"agents/main.json\"]",
                "\"agents\": [\"agents/main.json\", \"tools/build.json\"]"));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_DUPLICATE_REFERENCE));
    }

    private void createDirectoryLinkOrAbort(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | IOException e) {
            // fall back to a directory junction below
        }
        Process junction = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        junction.waitFor();
        if (junction.exitValue() != 0) {
            Assumptions.abort("symbolic links and junctions are unavailable in this test environment");
        }
    }

    @Test
    void rejectsFilesExceedingTheConfiguredSizeLimit() throws Exception {
        Path project = writeValidTree("size-limit-manifest");
        String manifest = Files.readString(configDir(project).resolve("agent.json"));

        ConfigReadException manifestTooLarge = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader(64).read(project));
        assertTrue(hasReasonAt(manifestTooLarge, AgentProjectConfigValidator.REASON_PATH_SIZE_LIMIT, "agent.json"));

        Path childProject = writeValidTree("size-limit-child");
        String bigTool = "{\"name\":\"big\",\"script\":\"scripts/big.ps1\",\"description\":\""
                + "x".repeat(manifest.length() + 100) + "\"}";
        Files.writeString(configDir(childProject).resolve("tools/build.json"), bigTool);

        ConfigReadException childTooLarge = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader(manifest.length() + 10).read(childProject));
        assertTrue(hasReasonAt(childTooLarge, AgentProjectConfigValidator.REASON_PATH_SIZE_LIMIT, "tools/build.json"));
    }

    @Test
    void rejectsMissingSchemaVersion() throws Exception {
        Path project = writeValidTree("no-schema");
        replaceManifest(project, MANIFEST.replaceFirst("\"schemaVersion\": 1,\\R", ""));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_MISSING_SCHEMA_VERSION));
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws Exception {
        Path project = writeValidTree("bad-schema");
        replaceManifest(project, MANIFEST.replace("\"schemaVersion\": 1,", "\"schemaVersion\": 2,"));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_UNSUPPORTED_SCHEMA_VERSION));
    }

    @Test
    void rejectsUnknownKeys() throws Exception {
        Path project = writeValidTree("unknown-key");
        replaceManifest(project, MANIFEST.replace("{\"enabledTools\": [\"read_file\"], \"deniedTools\": []}",
                "{\"enabledTools\": [\"read_file\"], \"deniedTools\": [], \"mystery\": true}"));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_UNKNOWN_KEY));
    }

    @Test
    void rejectsSecretLookingKeysWithoutExposingTheValue() throws Exception {
        Path project = writeValidTree("secret-key");
        replaceManifest(project, MANIFEST.replaceFirst("\\R  \"schemaVersion\": 1,",
                "\r\n  \"schemaVersion\": 1,\r\n  \"apiKey\": \"sk-exposed-value-98765\","));

        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_SECRET_KEY));
        String rendered = e.errors().stream().map(ValidationError::message).reduce("", String::concat);
        assertFalse(rendered.contains("sk-exposed-value-98765"));

        Path childSecret = writeValidTree("secret-child");
        Files.writeString(configDir(childSecret).resolve("mcp/typescript.json"),
                "{\"id\":\"ts\",\"command\":\"npx\",\"password\":\"p@ss-in-child\"}");
        ConfigReadException child = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader().read(childSecret));
        assertTrue(hasReasonAt(child, AgentProjectConfigValidator.REASON_SECRET_KEY, "mcp/typescript.json"));
    }

    @Test
    void rejectsUnsupportedAndUnsafeRuntimeProfiles() throws Exception {
        Path project = writeValidTree("bad-profile");
        replaceManifest(project, MANIFEST.replace("\"runtimeProfile\": \"strict\"", "\"runtimeProfile\": \"docker\""));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_PROFILE_UNSUPPORTED));

        Path unsafe = writeValidTree("unsafe-profile");
        replaceManifest(unsafe, MANIFEST.replace("\"runtimeProfile\": \"strict\"", "\"runtimeProfile\": \"unsafe-local\""));
        ConfigReadException denied = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader().read(unsafe));
        assertTrue(hasReason(denied, AgentProjectConfigValidator.REASON_PROFILE_DENIED));
    }

    @Test
    void rejectsHardDeniedCapabilities() throws Exception {
        Path project = writeValidTree("ceiling");
        replaceManifest(project, MANIFEST.replace("{\"enabledTools\": [\"read_file\"], \"deniedTools\": []}",
                "{\"enabledTools\": [\"read_file\"], \"deniedTools\": [], \"allowMetadataAccess\": true}"));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_CAPABILITY_ABOVE_CEILING));
    }

    @Test
    void rejectsDuplicateSemanticDefinitions() throws Exception {
        Path project = writeValidTree("duplicate-tool");
        Files.writeString(configDir(project).resolve("tools/another.json"),
                "{\"name\":\"build\",\"script\":\"scripts/other.ps1\"}");
        replaceManifest(project, MANIFEST.replace("\"tools\": [\"tools/build.json\"]",
                "\"tools\": [\"tools/build.json\", \"tools/another.json\"]"));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_DUPLICATE_DEFINITION));
    }

    @Test
    void rejectsInvalidReferences() throws Exception {
        Path project = writeValidTree("missing-child");
        replaceManifest(project, MANIFEST.replace("\"skills\": [\"skills/backend.md\"]",
                "\"skills\": [\"skills/backend.md\", \"skills/missing.md\"]"));
        ConfigReadException missing = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(missing, AgentProjectConfigValidator.REASON_REFERENCE_UNRESOLVABLE));

        Path badRef = writeValidTree("bad-ref");
        replaceManifest(badRef, MANIFEST.replace("\"environment\": \"environment.json\"",
                "\"environment\": \"envs/prod.json\""));
        ConfigReadException invalid = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader().read(badRef));
        assertTrue(hasReason(invalid, AgentProjectConfigValidator.REASON_REFERENCE_INVALID));

        Path badModelRef = writeValidTree("bad-model-ref");
        Files.writeString(configDir(badModelRef).resolve("agents/main.json"),
                "{\"id\":\"main\",\"modelRef\":\"ghost-model\"}");
        ConfigReadException model = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader().read(badModelRef));
        assertTrue(hasReason(model, AgentProjectConfigValidator.REASON_REFERENCE_UNRESOLVABLE));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        Path project = writeValidTree("malformed-manifest");
        replaceManifest(project, "{\"schemaVersion\": 1,");
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReasonAt(e, AgentProjectConfigValidator.REASON_MALFORMED_JSON, "agent.json"));

        Path childProject = writeValidTree("malformed-child");
        Files.writeString(configDir(childProject).resolve("mcp/typescript.json"), "not json at all");
        ConfigReadException child = assertThrows(ConfigReadException.class,
                () -> new AgentProjectConfigReader().read(childProject));
        assertTrue(hasReasonAt(child, AgentProjectConfigValidator.REASON_MALFORMED_JSON, "mcp/typescript.json"));
    }

    @Test
    void rejectsInlineSecretValuesInEnvironmentTemplate() throws Exception {
        Path project = writeValidTree("inline-secret");
        Files.writeString(configDir(project).resolve("environment.json"),
                "{\"variables\":[\"NODE_ENV\",\"DB_PASSWORD=supersecret\"],\"installPolicy\":\"project-only\"}");
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReason(e, AgentProjectConfigValidator.REASON_SECRET_VALUE));
    }

    @Test
    void formattingOnlyChangesKeepTheSameDigest() throws Exception {
        Path project = writeValidTree("formatting-a");
        Path formatted = writeValidTree("formatting-b");
        replaceManifest(formatted, MANIFEST_FORMATTED);
        Files.writeString(configDir(formatted).resolve("agents/main.json"),
                "{\n  \"modelRef\": \"model-1\",\n  \"name\": \"Main Agent\",\n  \"instructions\": \"Be helpful\",\n  \"id\": \"main\"\n}");

        assertEquals(new AgentProjectConfigReader().read(project).sha256Digest(),
                new AgentProjectConfigReader().read(formatted).sha256Digest());
    }

    @Test
    void semanticChangesChangeTheDigest() throws Exception {
        Path project = writeValidTree("semantic-a");
        Path changed = writeValidTree("semantic-b");
        Files.writeString(configDir(changed).resolve("agents/main.json"),
                "{\"id\":\"main\",\"name\":\"Main Agent\",\"modelRef\":\"model-1\",\"instructions\":\"Be concise\"}");

        assertNotEquals(new AgentProjectConfigReader().read(project).sha256Digest(),
                new AgentProjectConfigReader().read(changed).sha256Digest());
    }

    @Test
    void rejectsManifestAsNonObjectJson() throws Exception {
        Path project = writeValidTree("array-manifest");
        replaceManifest(project, "[\"schemaVersion\", 1]");
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertTrue(hasReasonAt(e, AgentProjectConfigValidator.REASON_MALFORMED_JSON, "agent.json"));
    }

    @Test
    void readerRequiresAnExistingManifest() throws Exception {
        Path project = Files.createDirectories(root.resolve("empty-project"));
        ConfigReadException e = assertThrows(ConfigReadException.class, () -> new AgentProjectConfigReader().read(project));
        assertNotNull(e);
        assertFalse(e.errors().isEmpty());
    }
}
