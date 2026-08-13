package com.labex.labexagent.projectconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.LoadedChild;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ValidationError;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentProjectConfigValidatorTest {

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

    private final AgentProjectConfigValidator validator = new AgentProjectConfigValidator();

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static boolean hasReason(List<ValidationError> errors, String reasonCode) {
        return errors.stream().anyMatch(e -> reasonCode.equals(e.reasonCode()));
    }

    private static boolean hasAt(List<ValidationError> errors, String reasonCode, String filePath, String jsonPath) {
        return errors.stream().anyMatch(e -> reasonCode.equals(e.reasonCode())
                && filePath.equals(e.filePath()) && jsonPath.equals(e.jsonPath()));
    }

    @Test
    void validManifestIsAccepted() {
        assertTrue(validator.validateManifest(json(MANIFEST)).isEmpty());
    }

    @Test
    void missingSchemaVersionIsRejected() {
        JsonObject manifest = json(MANIFEST);
        manifest.remove("schemaVersion");
        List<ValidationError> errors = validator.validateManifest(manifest);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_MISSING_SCHEMA_VERSION,
                AgentProjectConfigValidator.MANIFEST, "$.schemaVersion"));
    }

    @Test
    void unsupportedSchemaVersionIsRejected() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("schemaVersion", 2);
        assertTrue(hasReason(validator.validateManifest(manifest),
                AgentProjectConfigValidator.REASON_UNSUPPORTED_SCHEMA_VERSION));
        manifest.addProperty("schemaVersion", 1.5);
        assertTrue(hasReason(validator.validateManifest(manifest),
                AgentProjectConfigValidator.REASON_UNSUPPORTED_SCHEMA_VERSION));
    }

    @Test
    void unknownKeyIsRejected() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("mysterySetting", true);
        List<ValidationError> errors = validator.validateManifest(manifest);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_UNKNOWN_KEY,
                AgentProjectConfigValidator.MANIFEST, "$.mysterySetting"));
    }

    @Test
    void secretLookingKeysAreRejectedWithoutEchoingTheValue() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("apiKey", "sk-super-secret-value-12345");
        List<ValidationError> errors = validator.validateManifest(manifest);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_SECRET_KEY,
                AgentProjectConfigValidator.MANIFEST, "$.apiKey"));
        String messages = errors.stream().map(ValidationError::message).reduce("", String::concat);
        assertFalse(messages.contains("sk-super-secret-value-12345"));
    }

    @Test
    void secretLookingKeysAreRejectedCaseInsensitively() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("Authorization", "Bearer abc");
        assertTrue(hasAt(validator.validateManifest(manifest), AgentProjectConfigValidator.REASON_SECRET_KEY,
                AgentProjectConfigValidator.MANIFEST, "$.Authorization"));
        JsonObject nested = json("{\"schemaVersion\":1,\"networkPolicy\":{\"allowLan\":false}}");
        nested.getAsJsonObject("networkPolicy").addProperty("refresh_token", "rt-abc");
        assertTrue(hasAt(validator.validateManifest(nested), AgentProjectConfigValidator.REASON_SECRET_KEY,
                AgentProjectConfigValidator.MANIFEST, "$.networkPolicy.refresh_token"));
    }

    @Test
    void unsupportedRuntimeProfileIsRejected() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("runtimeProfile", "docker");
        assertTrue(hasReason(validator.validateManifest(manifest),
                AgentProjectConfigValidator.REASON_PROFILE_UNSUPPORTED));
    }

    @Test
    void unsafeLocalProfileIsHardDenied() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("runtimeProfile", "unsafe-local");
        assertTrue(hasReason(validator.validateManifest(manifest),
                AgentProjectConfigValidator.REASON_PROFILE_DENIED));
    }

    @Test
    void hardDeniedCapabilitiesAreRejected() {
        JsonObject manifest = json(MANIFEST);
        manifest.getAsJsonObject("capabilities").addProperty("allowSecretAccess", true);
        assertTrue(hasAt(validator.validateManifest(manifest), AgentProjectConfigValidator.REASON_CAPABILITY_ABOVE_CEILING,
                AgentProjectConfigValidator.MANIFEST, "$.capabilities.allowSecretAccess"));

        JsonObject host = json(MANIFEST);
        host.getAsJsonObject("capabilities").addProperty("allowHostMaintenance", true);
        host.getAsJsonObject("capabilities").addProperty("allowWslShutdown", true);
        List<ValidationError> errors = validator.validateManifest(host);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_CAPABILITY_ABOVE_CEILING,
                AgentProjectConfigValidator.MANIFEST, "$.capabilities.allowHostMaintenance"));
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_CAPABILITY_ABOVE_CEILING,
                AgentProjectConfigValidator.MANIFEST, "$.capabilities.allowWslShutdown"));
    }

    @Test
    void hardDeniedCapabilitiesSetToFalseAreAccepted() {
        JsonObject manifest = json(MANIFEST);
        manifest.getAsJsonObject("capabilities").addProperty("allowSecretAccess", false);
        manifest.getAsJsonObject("capabilities").addProperty("allowProtectedConfigWrites", false);
        assertTrue(validator.validateManifest(manifest).isEmpty());
    }

    @Test
    void duplicateManifestReferencesAreRejected() {
        JsonObject manifest = json(MANIFEST);
        manifest.add("agents", JsonParser.parseString("[\"agents/main.json\", \"agents/main.json\"]"));
        assertTrue(hasAt(validator.validateManifest(manifest), AgentProjectConfigValidator.REASON_DUPLICATE_REFERENCE,
                AgentProjectConfigValidator.MANIFEST, "$.agents[1]"));
    }

    @Test
    void defaultAgentMustBeDeclaredInAgents() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("defaultAgent", "agents/other.json");
        assertTrue(hasAt(validator.validateManifest(manifest), AgentProjectConfigValidator.REASON_DEFAULT_AGENT_UNDECLARED,
                AgentProjectConfigValidator.MANIFEST, "$.defaultAgent"));
    }

    @Test
    void requiredManifestKeysAreEnforced() {
        JsonObject manifest = json(MANIFEST);
        manifest.remove("defaultAgent");
        assertTrue(hasAt(validator.validateManifest(manifest), AgentProjectConfigValidator.REASON_MISSING_REQUIRED_KEY,
                AgentProjectConfigValidator.MANIFEST, "$.defaultAgent"));
    }

    @Test
    void typeMismatchesAreRejected() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("runtimeProfile", 7);
        assertTrue(hasAt(validator.validateManifest(manifest), AgentProjectConfigValidator.REASON_TYPE_MISMATCH,
                AgentProjectConfigValidator.MANIFEST, "$.runtimeProfile"));
        JsonObject badAgents = json(MANIFEST);
        badAgents.addProperty("agents", "agents/main.json");
        assertTrue(hasAt(validator.validateManifest(badAgents), AgentProjectConfigValidator.REASON_TYPE_MISMATCH,
                AgentProjectConfigValidator.MANIFEST, "$.agents"));
        JsonObject badEntry = json(MANIFEST);
        badEntry.add("agents", JsonParser.parseString("[\"agents/main.json\", 7]"));
        assertTrue(hasAt(validator.validateManifest(badEntry), AgentProjectConfigValidator.REASON_TYPE_MISMATCH,
                AgentProjectConfigValidator.MANIFEST, "$.agents[1]"));
    }

    @Test
    void duplicateToolNamesAcrossChildFilesAreRejected() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.json("tools/a.json", AgentProjectConfigValidator.ChildKind.TOOL,
                        json("{\"name\":\"build\",\"script\":\"scripts/a.ps1\"}")),
                LoadedChild.json("tools/b.json", AgentProjectConfigValidator.ChildKind.TOOL,
                        json("{\"name\":\"build\",\"script\":\"scripts/b.ps1\"}")));
        List<ValidationError> errors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children);
        assertTrue(errors.stream().anyMatch(e -> AgentProjectConfigValidator.REASON_DUPLICATE_DEFINITION.equals(e.reasonCode())
                && "tools/b.json".equals(e.filePath())));
    }

    @Test
    void duplicateAgentAndMcpIdsAreRejected() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> agents = List.of(
                LoadedChild.json("agents/a.json", AgentProjectConfigValidator.ChildKind.AGENT, json("{\"id\":\"main\"}")),
                LoadedChild.json("agents/b.json", AgentProjectConfigValidator.ChildKind.AGENT, json("{\"id\":\"main\"}")));
        assertTrue(hasReason(validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, agents),
                AgentProjectConfigValidator.REASON_DUPLICATE_DEFINITION));
        List<LoadedChild> mcp = List.of(
                LoadedChild.json("mcp/a.json", AgentProjectConfigValidator.ChildKind.MCP, json("{\"id\":\"ts\",\"command\":\"npx\"}")),
                LoadedChild.json("mcp/b.json", AgentProjectConfigValidator.ChildKind.MCP, json("{\"id\":\"ts\",\"command\":\"tsc\"}")));
        assertTrue(hasReason(validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, mcp),
                AgentProjectConfigValidator.REASON_DUPLICATE_DEFINITION));
    }

    @Test
    void unresolvableModelReferenceIsRejected() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.json("agents/main.json", AgentProjectConfigValidator.ChildKind.AGENT,
                        json("{\"id\":\"main\",\"modelRef\":\"ghost-model\"}")));
        List<ValidationError> errors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_REFERENCE_UNRESOLVABLE,
                "agents/main.json", "$.modelRef"));
    }

    @Test
    void invalidChildReferenceFormatIsRejected() {
        JsonObject manifest = json(MANIFEST);
        List<ValidationError> wrongExtension = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest,
                List.of(LoadedChild.json("agents/x.txt", AgentProjectConfigValidator.ChildKind.AGENT, json("{\"id\":\"x\"}"))));
        assertTrue(hasReason(wrongExtension, AgentProjectConfigValidator.REASON_REFERENCE_INVALID));
        List<ValidationError> wrongPrefix = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest,
                List.of(LoadedChild.json("custom/place.json", AgentProjectConfigValidator.ChildKind.TOOL,
                        json("{\"name\":\"x\",\"script\":\"s\"}"))));
        assertTrue(hasReason(wrongPrefix, AgentProjectConfigValidator.REASON_REFERENCE_INVALID));
        List<ValidationError> wrongEnv = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest,
                List.of(LoadedChild.json("envs/prod.json", AgentProjectConfigValidator.ChildKind.ENVIRONMENT,
                        json("{\"variables\":[]}"))));
        assertTrue(hasReason(wrongEnv, AgentProjectConfigValidator.REASON_REFERENCE_INVALID));
    }

    @Test
    void secretLookingKeysInChildFilesAreRejected() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.json("mcp/typescript.json", AgentProjectConfigValidator.ChildKind.MCP,
                        json("{\"id\":\"ts\",\"command\":\"npx\",\"api_key\":\"k-123\"}")));
        List<ValidationError> errors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_SECRET_KEY, "mcp/typescript.json", "$.api_key"));
        String messages = errors.stream().map(ValidationError::message).reduce("", String::concat);
        assertFalse(messages.contains("k-123"));
    }

    @Test
    void credentialAliasIsAllowedInMcpDocuments() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.json("mcp/typescript.json", AgentProjectConfigValidator.ChildKind.MCP,
                        json("{\"id\":\"ts\",\"command\":\"npx\",\"credentialAlias\":\"ts-credential\"}")));
        assertTrue(validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children).isEmpty());
    }

    @Test
    void inlineEnvironmentValuesAreRejectedAsSecrets() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.json("environment.json", AgentProjectConfigValidator.ChildKind.ENVIRONMENT,
                        json("{\"variables\":[\"NODE_ENV=prod\"]}")));
        List<ValidationError> errors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_SECRET_VALUE,
                "environment.json", "$.variables[0]"));
        JsonObject mcpManifest = json(MANIFEST);
        List<LoadedChild> mcpChildren = List.of(
                LoadedChild.json("mcp/typescript.json", AgentProjectConfigValidator.ChildKind.MCP,
                        json("{\"id\":\"ts\",\"command\":\"npx\",\"envNames\":[\"TOKEN=abc\"]}")));
        assertTrue(hasReason(validator.validate(AgentProjectConfigValidator.MANIFEST, mcpManifest, mcpChildren),
                AgentProjectConfigValidator.REASON_SECRET_VALUE));
    }

    @Test
    void missingSemanticIdOrNameIsRejected() {
        JsonObject manifest = json(MANIFEST);
        List<ValidationError> errors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest,
                List.of(LoadedChild.json("agents/main.json", AgentProjectConfigValidator.ChildKind.AGENT,
                        json("{\"name\":\"No Id\"}"))));
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_MISSING_REQUIRED_KEY,
                "agents/main.json", "$.id"));
        List<ValidationError> toolErrors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest,
                List.of(LoadedChild.json("tools/build.json", AgentProjectConfigValidator.ChildKind.TOOL,
                        json("{\"script\":\"scripts/build.ps1\"}"))));
        assertTrue(hasAt(toolErrors, AgentProjectConfigValidator.REASON_MISSING_REQUIRED_KEY,
                "tools/build.json", "$.name"));
    }

    @Test
    void agentToolReferencesMustResolveToDeclaredToolNames() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.json("agents/main.json", AgentProjectConfigValidator.ChildKind.AGENT,
                        json("{\"id\":\"main\",\"tools\":[\"build\",\"ghost-tool\"]}")),
                LoadedChild.json("tools/build.json", AgentProjectConfigValidator.ChildKind.TOOL,
                        json("{\"name\":\"build\",\"script\":\"scripts/build.ps1\"}")));
        List<ValidationError> errors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_REFERENCE_UNRESOLVABLE,
                "agents/main.json", "$.tools[1]"));
        assertFalse(errors.stream().anyMatch(e -> "$.tools[0]".equals(e.jsonPath())));
    }

    @Test
    void agentToolAllowlistResolvesToDeclaredTools() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.json("agents/main.json", AgentProjectConfigValidator.ChildKind.AGENT,
                        json("{\"id\":\"main\",\"tools\":[\"build\"]}")),
                LoadedChild.json("tools/build.json", AgentProjectConfigValidator.ChildKind.TOOL,
                        json("{\"name\":\"build\",\"script\":\"scripts/build.ps1\"}")));
        assertTrue(validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children).isEmpty());
    }

    @Test
    void errorMessagesCannotCarryNewlinesFromRejectedValues() {
        JsonObject manifest = json(MANIFEST);
        manifest.addProperty("runtimeProfile", "docker\r\nINJECTED-LOG-LINE");
        List<ValidationError> errors = validator.validateManifest(manifest);
        assertTrue(hasReason(errors, AgentProjectConfigValidator.REASON_PROFILE_UNSUPPORTED));
        String messages = errors.stream().map(ValidationError::message).reduce("", String::concat);
        assertFalse(messages.contains("\n"));
        assertFalse(messages.contains("\r"));

        JsonObject badKey = json(MANIFEST);
        badKey.add("bad\nkey", JsonParser.parseString("true"));
        List<ValidationError> keyErrors = validator.validateManifest(badKey);
        String keyMessages = keyErrors.stream().map(ValidationError::message).reduce("", String::concat);
        assertFalse(keyMessages.contains("\n"));
    }

    @Test
    void unknownKeysInChildDocumentsAreRejected() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.json("tools/build.json", AgentProjectConfigValidator.ChildKind.TOOL,
                        json("{\"name\":\"build\",\"script\":\"scripts/build.ps1\",\"surprise\":1}")));
        List<ValidationError> errors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children);
        assertTrue(hasAt(errors, AgentProjectConfigValidator.REASON_UNKNOWN_KEY,
                "tools/build.json", "$.surprise"));
    }

    @Test
    void duplicateSkillSlugsAreRejected() {
        JsonObject manifest = json(MANIFEST);
        List<LoadedChild> children = List.of(
                LoadedChild.text("skills/backend.md", "# Backend"),
                LoadedChild.text("skills/nested/backend.md", "# Backend"));
        List<ValidationError> errors = validator.validate(AgentProjectConfigValidator.MANIFEST, manifest, children);
        assertEquals(AgentProjectConfigValidator.REASON_DUPLICATE_DEFINITION, errors.get(0).reasonCode());
    }
}
