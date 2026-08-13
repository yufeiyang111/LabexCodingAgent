package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentProjectConfigRevision;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentEffectiveProjectConfigService.EffectiveConfigException;
import com.labex.labexagent.projectconfig.AgentEffectiveProjectConfigService.EffectiveProjectConfig;
import com.labex.labexagent.projectconfig.AgentProjectConfigExternalChangeService.PendingExternalChange;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentEffectiveProjectConfigServiceTest {

    @TempDir
    Path tempDir;

    private StudentProject project;
    private AgentProjectConfigRevisionMapper revisionMapper;
    private AgentProjectConfigExternalChangeService externalChangeService;
    private AgentEffectiveProjectConfigService service;

    @BeforeEach
    void setUp() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path configDir = Files.createDirectories(projectRoot.resolve(".labex-agent/project"));
        Files.createDirectories(configDir.resolve("agents"));
        Files.createDirectories(configDir.resolve("mcp"));
        Files.writeString(configDir.resolve("agent.json"), """
                {
                  "schemaVersion": 1,
                  "defaultAgent": "agents/agent-a.json",
                  "agents": ["agents/agent-a.json"],
                  "models": ["model-1"],
                  "mcpServers": ["mcp/typescript.json"],
                  "runtimeProfile": "strict",
                  "environment": "environment.json"
                }
                """);
        Files.writeString(configDir.resolve("agents/agent-a.json"), """
                {"id": "agent-a", "name": "Agent A"}
                """);
        Files.writeString(configDir.resolve("mcp/typescript.json"), """
                {"id": "ts", "name": "TypeScript", "command": "npx",
                 "args": ["--api-key", "sk-leak-abc"]}
                """);
        Files.writeString(configDir.resolve("environment.json"), """
                {"variables": ["API_KEY", "BASE_URL"], "installPolicy": "project-only"}
                """);

        StudentProjectService studentProjectService = mock(StudentProjectService.class);
        project = new StudentProject();
        project.setStudentId(7);
        project.setProjectId(12);
        project.setWorkspacePath(projectRoot.toString());
        when(studentProjectService.getOwnedProject(7, 12)).thenReturn(project);

        revisionMapper = mock(AgentProjectConfigRevisionMapper.class);
        externalChangeService = mock(AgentProjectConfigExternalChangeService.class);
        AgentProjectConfigRevisionService revisionService =
                new AgentProjectConfigRevisionService(studentProjectService, revisionMapper, externalChangeService);
        service = new AgentEffectiveProjectConfigService(revisionService);
    }

    private AgentProjectConfigRevision acceptedRevision(String treeReference) {
        AgentProjectConfigRevision revision = new AgentProjectConfigRevision();
        revision.setRevision(1L);
        revision.setConfigDigest("some-digest");
        revision.setTreeReference(treeReference);
        revision.setNormalizedConfig("{}");
        return revision;
    }

    @Test
    void resolvesTheCompleteNonSecretEffectiveDocumentForAnOwnedProject() {
        when(revisionMapper.selectOne(any())).thenReturn(null);
        when(revisionMapper.insert(any())).thenReturn(1);

        EffectiveProjectConfig effective = service.resolve(7, project, 42, "plan");

        assertThat(effective.projectConfigRevision()).isEqualTo(1L);
        assertThat(effective.effectiveConfigJson())
                .contains("\"modelConfigId\":42")
                .contains("\"mode\":\"plan\"")
                .contains("agents/agent-a.json")
                .contains("\"runtimeProfile\":\"strict\"")
                .contains("API_KEY")
                .doesNotContain("sk-")
                .doesNotContain("sk-leak-abc");
        assertThat(effective.effectiveConfigJson()).contains("\"***\"");
        assertThat(effective.effectiveConfigDigest()).isNotBlank();
        assertThat(effective.modelFingerprint()).isNotBlank();
        assertThat(effective.capabilityDigest()).isNotBlank();
        assertThat(effective.resourceDigest()).isNotBlank();
        assertThat(effective.runtimeProfile()).isEqualTo("strict");
        assertThat(effective.secretAliasesJson()).contains("API_KEY").contains("BASE_URL");
    }

    @Test
    void fingerprintsAreStableForIdenticalInputsAndChangeWithTheModelSelection() {
        when(revisionMapper.selectOne(any())).thenReturn(null);
        when(revisionMapper.insert(any())).thenReturn(1);

        EffectiveProjectConfig first = service.resolve(7, project, 42, "plan");
        EffectiveProjectConfig second = service.resolve(7, project, 42, "plan");
        EffectiveProjectConfig otherModel = service.resolve(7, project, 43, "plan");

        assertThat(second.modelFingerprint()).isEqualTo(first.modelFingerprint());
        assertThat(second.effectiveConfigDigest()).isEqualTo(first.effectiveConfigDigest());
        assertThat(otherModel.modelFingerprint()).isNotEqualTo(first.modelFingerprint());
        assertThat(otherModel.effectiveConfigDigest()).isNotEqualTo(first.effectiveConfigDigest());
    }

    @Test
    void childContentChangesChangeTheEffectiveDigest() throws Exception {
        when(revisionMapper.selectOne(any())).thenReturn(null);
        when(revisionMapper.insert(any())).thenReturn(1);

        EffectiveProjectConfig first = service.resolve(7, project, 42, "plan");
        Files.writeString(tempDir.resolve("workspace/.labex-agent/project/agents/agent-a.json"),
                "{\"id\": \"agent-a\", \"name\": \"Agent A v2\"}");
        when(revisionMapper.selectOne(any())).thenReturn(null);

        EffectiveProjectConfig second = service.resolve(7, project, 42, "plan");

        assertThat(second.effectiveConfigDigest()).isNotEqualTo(first.effectiveConfigDigest());
    }

    @Test
    void resolvesPlatformDefaultsWhenTheProjectHasNoConfigPackage() throws Exception {
        Files.deleteIfExists(tempDir.resolve("workspace/.labex-agent/project/agent.json"));
        Files.deleteIfExists(tempDir.resolve("workspace/.labex-agent/project/agents/agent-a.json"));
        Files.deleteIfExists(tempDir.resolve("workspace/.labex-agent/project/mcp/typescript.json"));
        Files.deleteIfExists(tempDir.resolve("workspace/.labex-agent/project/environment.json"));
        Files.deleteIfExists(tempDir.resolve("workspace/.labex-agent/project/agents"));
        Files.deleteIfExists(tempDir.resolve("workspace/.labex-agent/project/mcp"));
        Files.deleteIfExists(tempDir.resolve("workspace/.labex-agent/project"));

        when(revisionMapper.selectOne(any())).thenReturn(null);

        EffectiveProjectConfig effective = service.resolve(7, project, 42, "plan");

        assertThat(effective.projectConfigRevision()).isNull();
        assertThat(effective.projectConfigDigest()).isNull();
        assertThat(effective.runtimeProfile()).isEqualTo("strict");
        assertThat(effective.effectiveConfigJson())
                .contains("\"modelConfigId\":42")
                .contains("\"mode\":\"plan\"")
                .doesNotContain("sk-");
        assertThat(effective.effectiveConfigDigest()).isNotBlank();
        assertThat(effective.modelFingerprint()).isNotBlank();
        assertThat(effective.secretAliasesJson()).isEqualTo("[]");
    }

    @Test
    void failsClosedWhenTheConfigurationTreeIsInvalid() throws Exception {
        Files.writeString(tempDir.resolve("workspace/.labex-agent/project/agent.json"),
                "{\"schemaVersion\": 1}");

        when(revisionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.resolve(7, project, 42, "plan"))
                .isInstanceOf(EffectiveConfigException.class)
                .hasMessageContaining("config-invalid");
    }

    @Test
    void failsClosedWhenAnExternalChangeIsPending() {
        when(revisionMapper.selectOne(any())).thenReturn(acceptedRevision("tree:stale-digest"));
        when(externalChangeService.recordPending(any(Integer.class), any(Integer.class),
                any(Long.class), any(String.class), anyList()))
                .thenReturn(new PendingExternalChange(99L, 1L, "observed", "agent.json",
                        LocalDateTime.now()));

        assertThatThrownBy(() -> service.resolve(7, project, 42, "plan"))
                .isInstanceOf(EffectiveConfigException.class)
                .hasMessageContaining("external-change-pending");
    }
}
