package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigChangeEvidenceService.EvidenceRef;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership.ProjectConfigNotFoundException;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentProjectConfigChangeEvidenceServiceTest {

    @TempDir
    Path root;

    private StudentProjectService studentProjectService;
    private AgentProjectConfigChangeEvidenceService service;

    @BeforeEach
    void setUp() {
        studentProjectService = mock(StudentProjectService.class);
        service = new AgentProjectConfigChangeEvidenceService(studentProjectService);
    }

    @Test
    void recordEvidenceWritesDurableSecretFreeJsonAndReturnsStableRef() throws Exception {
        Path project = root.resolve("evidence-project");
        Files.createDirectories(project);
        registerOwnedProject(7, 12, project);

        EvidenceRef ref = service.recordEvidence(7, 12, 99L, 1L,
                "a".repeat(64), "b".repeat(64), List.of("agents/main.json", "environment.json"));

        assertThat(ref.id()).isEqualTo("proposal-99-" + "b".repeat(16));
        assertThat(ref.proposalId()).isEqualTo(99L);
        assertThat(ref.baseRevision()).isEqualTo(1L);
        assertThat(ref.beforeTreeDigest()).isEqualTo("a".repeat(64));
        assertThat(ref.afterTreeDigest()).isEqualTo("b".repeat(64));
        assertThat(ref.changedPaths()).containsExactly("agents/main.json", "environment.json");
        assertThat(ref.recordedAt()).isNotNull();

        Path file = project.resolve(".labex-agent/config-evidence/" + ref.id() + ".json");
        assertThat(file).exists();
        String content = Files.readString(file);
        JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
        assertThat(parsed.get("evidenceId").getAsString()).isEqualTo(ref.id());
        assertThat(parsed.get("proposalId").getAsLong()).isEqualTo(99L);
        assertThat(parsed.get("beforeTreeDigest").getAsString()).isEqualTo("a".repeat(64));
        assertThat(parsed.get("afterTreeDigest").getAsString()).isEqualTo("b".repeat(64));
        JsonArray paths = parsed.getAsJsonArray("changedPaths");
        assertThat(paths).hasSize(2);
        assertThat(paths.get(0).getAsString()).isEqualTo("agents/main.json");
        assertThat(content).doesNotContain("instructions", "Be helpful", "sk-", "credential");
    }

    @Test
    void recordEvidenceIsIdempotentForTheSameProposalAndAfterDigest() throws Exception {
        Path project = root.resolve("idempotent");
        Files.createDirectories(project);
        registerOwnedProject(7, 12, project);

        EvidenceRef first = service.recordEvidence(7, 12, 99L, 1L,
                "a".repeat(64), "b".repeat(64), List.of("agents/main.json"));
        EvidenceRef second = service.recordEvidence(7, 12, 99L, 1L,
                "a".repeat(64), "b".repeat(64), List.of("agents/main.json"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.recordedAt()).isEqualTo(first.recordedAt());
        Path dir = project.resolve(".labex-agent/config-evidence");
        try (var stream = Files.list(dir)) {
            assertThat(stream.count()).isEqualTo(1);
        }
    }

    @Test
    void loadEvidenceRoundTripsTheRecordedRef() throws Exception {
        Path project = root.resolve("roundtrip");
        Files.createDirectories(project);
        registerOwnedProject(7, 12, project);

        EvidenceRef recorded = service.recordEvidence(7, 12, 99L, 3L,
                "c".repeat(64), "d".repeat(64), List.of("agent.json"));

        Optional<EvidenceRef> loaded = service.loadEvidence(7, 12, recorded.id());

        assertThat(loaded).isPresent();
        assertThat(loaded.get().proposalId()).isEqualTo(99L);
        assertThat(loaded.get().baseRevision()).isEqualTo(3L);
        assertThat(loaded.get().beforeTreeDigest()).isEqualTo("c".repeat(64));
        assertThat(loaded.get().afterTreeDigest()).isEqualTo("d".repeat(64));
        assertThat(loaded.get().changedPaths()).containsExactly("agent.json");
        assertThat(loaded.get().recordedAt()).isEqualTo(recorded.recordedAt());
    }

    @Test
    void evidenceForForeignProjectFailsClosedAsNotFound() {
        when(studentProjectService.getOwnedProject(7, 999)).thenReturn(null);

        ProjectConfigNotFoundException error = catchThrowableOfType(
                () -> service.recordEvidence(7, 999, 1L, 1L,
                        "a".repeat(64), "b".repeat(64), List.of()),
                ProjectConfigNotFoundException.class);

        assertThat(error.getMessage()).isEqualTo("Project not found");
    }

    @Test
    void loadOfUnknownEvidenceIdReturnsEmpty() throws Exception {
        Path project = root.resolve("unknown");
        Files.createDirectories(project);
        registerOwnedProject(7, 12, project);

        assertThat(service.loadEvidence(7, 12, "proposal-1-ffffffffffffffff")).isEmpty();
    }

    @Test
    void traversalEvidenceIdIsRejectedBeforePathResolution() throws Exception {
        Path project = root.resolve("traversal");
        Files.createDirectories(project);
        registerOwnedProject(7, 12, project);
        Path evidenceDir = project.resolve(".labex-agent/config-evidence");
        Files.createDirectories(evidenceDir);
        Files.writeString(evidenceDir.resolve("proposal-1-ffffffffffffffff.json"),
                "{\"evidenceId\":\"proposal-1-ffffffffffffffff\",\"proposalId\":1}");

        IllegalArgumentException error = catchThrowableOfType(
                () -> service.loadEvidence(7, 12, "../x"), IllegalArgumentException.class);

        assertThat(error).isNotNull();
        assertThat(service.loadEvidence(7, 12, "proposal-1-ffffffffffffffff")).isPresent();
    }

    private void registerOwnedProject(int studentId, int projectId, Path workspaceRoot) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(studentId);
        project.setWorkspacePath(workspaceRoot.toString());
        when(studentProjectService.getOwnedProject(studentId, projectId)).thenReturn(project);
    }
}
