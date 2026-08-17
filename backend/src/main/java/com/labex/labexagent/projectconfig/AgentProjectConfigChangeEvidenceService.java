package com.labex.labexagent.projectconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.StudentProject;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Durable, secret-free change evidence for one project configuration apply.
 *
 * <p>Each evidence document records only the proposal reference, the accepted base revision,
 * the before/after protected-tree digests and the redacted changed-path summary. It never
 * contains file contents, secret values, provider output or command lines. The document is
 * written as a JSON file beneath {@code .labex-agent/config-evidence/} (outside the protected
 * config tree, so it never pollutes the tree digest) using the same temp + fsync + atomic
 * move discipline as {@link AgentProjectConfigFileWriter}.
 *
 * <p>The evidence id is deterministic ({@code proposal-<proposalId>-<afterDigest[0..16]>}), so
 * recording after a crash point is idempotent: re-recording the same proposal and after digest
 * returns the first durable document. Every entry point requires the
 * {@link AgentProjectConfigOwnership} gate, so foreign projects are indistinguishable from not
 * found.
 */
@Component
public class AgentProjectConfigChangeEvidenceService {

    /** Evidence location relative to the project workspace root. */
    public static final String EVIDENCE_RELATIVE_DIR = ".labex-agent/config-evidence";

    /** Evidence ids are service-generated; any other shape is rejected before path resolution. */
    private static final Pattern SAFE_EVIDENCE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    /** One durable evidence reference. */
    public record EvidenceRef(String id, Long proposalId, Long baseRevision,
                              String beforeTreeDigest, String afterTreeDigest,
                              List<String> changedPaths, LocalDateTime recordedAt) {
    }
    private final StudentProjectService studentProjectService;

    public AgentProjectConfigChangeEvidenceService(StudentProjectService studentProjectService) {
        this.studentProjectService = studentProjectService;
    }

    /** Deterministic evidence id for a proposal apply; stable across recovery retries. */
    public String evidenceId(Long proposalId, String afterTreeDigest) {
        String head = afterTreeDigest == null ? "" : afterTreeDigest.substring(0,
                Math.min(16, afterTreeDigest.length()));
        return "proposal-" + proposalId + "-" + head;
    }

    /**
     * Records or returns the durable evidence document for one apply. Idempotent: a second
     * record for the same proposal and after digest returns the first document unchanged.
     */
    public EvidenceRef recordEvidence(Integer studentId, Integer projectId, Long proposalId,
                                      Long baseRevision, String beforeTreeDigest,
                                      String afterTreeDigest, List<String> changedPaths) {
        StudentProject project = AgentProjectConfigOwnership.requireOwned(studentId, projectId,
                studentProjectService);
        String id = evidenceId(proposalId, afterTreeDigest);
        Optional<EvidenceRef> existing = loadEvidence(studentId, projectId, id);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime recordedAt = LocalDateTime.now();
        JsonObject document = new JsonObject();
        document.addProperty("evidenceId", id);
        document.addProperty("proposalId", proposalId);
        document.addProperty("baseRevision", baseRevision);
        document.addProperty("beforeTreeDigest", beforeTreeDigest);
        document.addProperty("afterTreeDigest", afterTreeDigest);
        JsonArray paths = new JsonArray();
        if (changedPaths != null) {
            changedPaths.forEach(paths::add);
        }
        document.add("changedPaths", paths);
        document.addProperty("recordedAt", recordedAt.toString());
        writeAtomic(evidenceFile(project, id), document.toString());
        return new EvidenceRef(id, proposalId, baseRevision, beforeTreeDigest, afterTreeDigest,
                changedPaths == null ? List.of() : List.copyOf(changedPaths), recordedAt);
    }

    /** Loads the durable evidence document, or empty when it does not exist. */
    public Optional<EvidenceRef> loadEvidence(Integer studentId, Integer projectId, String evidenceId) {
        AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        if (evidenceId == null || evidenceId.isBlank()) {
            return Optional.empty();
        }
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        Path file = evidenceFile(project, evidenceId);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }        try {
            JsonObject parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            List<String> paths = new java.util.ArrayList<>();
            if (parsed.has("changedPaths") && parsed.get("changedPaths").isJsonArray()) {
                parsed.getAsJsonArray("changedPaths").forEach(element ->
                        paths.add(element.getAsString()));
            }
            LocalDateTime recordedAt = parsed.has("recordedAt")
                    ? LocalDateTime.parse(parsed.get("recordedAt").getAsString())
                    : null;
            return Optional.of(new EvidenceRef(
                    parsed.get("evidenceId").getAsString(),
                    parsed.get("proposalId").getAsLong(),
                    parsed.has("baseRevision") ? parsed.get("baseRevision").getAsLong() : null,
                    parsed.has("beforeTreeDigest") ? parsed.get("beforeTreeDigest").getAsString() : null,
                    parsed.has("afterTreeDigest") ? parsed.get("afterTreeDigest").getAsString() : null,
                    List.copyOf(paths),
                    recordedAt));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private Path evidenceFile(StudentProject project, String evidenceId) {
        if (evidenceId == null || !SAFE_EVIDENCE_ID.matcher(evidenceId).matches()) {
            throw new IllegalArgumentException("invalid evidence id: " + evidenceId);
        }
        Path evidenceDir = Path.of(project.getWorkspacePath()).resolve(EVIDENCE_RELATIVE_DIR).normalize();
        return evidenceDir.resolve(evidenceId + ".json").normalize();
    }

    private void writeAtomic(Path file, String content) throws RuntimeException {
        Path dir = file.getParent();
        try {
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "." + file.getFileName() + ".tmp-", ".tmp");
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(temp,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    channel.write(java.nio.ByteBuffer.wrap(bytes));
                    channel.force(true);
                }
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("unable to persist config change evidence", e);
        }
    }
}
