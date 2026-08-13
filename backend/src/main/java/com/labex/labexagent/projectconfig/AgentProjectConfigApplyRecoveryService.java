package com.labex.labexagent.projectconfig;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentProjectConfigAuditEvent;
import com.labex.entity.AgentProjectConfigExternalChange;
import com.labex.entity.AgentProjectConfigProposal;
import com.labex.entity.AgentProjectConfigRevision;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ChildKind;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.LoadedChild;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ValidationError;
import com.labex.labexagent.projectconfig.ProtectedProjectConfigPath.PathViolationException;
import com.labex.mapper.AgentProjectConfigAuditEventMapper;
import com.labex.mapper.AgentProjectConfigExternalChangeMapper;
import com.labex.mapper.AgentProjectConfigProposalMapper;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Recoverable apply authority for one owner-approved project configuration proposal.
 *
 * <p>An approval is a recoverable state machine, never a single database transaction over the
 * filesystem: the decision service CASes {@code pending -> applying}, then this service (a)
 * verifies the durable staged candidate tree, (b) records secret-free change evidence, (c)
 * publishes the staged tree through {@link AgentProjectConfigFileWriter} with post-publish
 * verification, (d) inserts the next revision row, and (e) CASes {@code applying -> applied}
 * with an idempotent audit event. {@link #reconcile} resumes the same idempotent apply from
 * any crash point and never exposes a false applied revision: a revision row only exists
 * after the published tree was verified byte-for-byte against the approved candidate digest,
 * and {@code applied} only exists after that revision row exists.
 *
 * <p>Reconciliation is forward-only: it converges the protected tree to the owner-approved
 * candidate and never reverts an externally visible tree to the old head. Conflicts (the
 * accepted head moved while the apply was in flight), missing or invalid staging, and
 * post-publish verification failures become a typed {@code failed} state with an audit event;
 * the tree then remains externally visible and blocks task snapshots through the normal
 * external-change detection until the owner resolves it.
 *
 * <p>Scope note (Task 2.2): {@code reconcile} is invoked from the decision path and from
 * explicit recovery calls; there is deliberately NO periodic reconciler in 2.2, so a crash
 * that strands a proposal in {@code applying} waits for the next decision or recovery call.
 * Task 2.3's interaction-claim recovery contract must own durable resume/reconcile of
 * stranded {@code applying} proposals — the same bounded, lease-guarded shape as the
 * Task 0.8 expired-lease reconciler — before any scheduler is added here.
 */
@Component
public final class AgentProjectConfigApplyRecoveryService {

    public static final String STATUS_APPLYING = "applying";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_FAILED = "failed";

    public static final String FAILURE_STAGING_UNAVAILABLE = "STAGING_UNAVAILABLE";
    public static final String FAILURE_CANDIDATE_MISMATCH = "CANDIDATE_MISMATCH";
    public static final String FAILURE_VALIDATION = "VALIDATION_FAILED";
    public static final String FAILURE_VERIFICATION = "VERIFICATION_FAILED";
    public static final String FAILURE_CONFLICT = "CONFLICT";

    /** One durable apply outcome or in-flight step result. */
    public record ApplyResult(Long proposalId, String status, Long appliedRevision,
                              String failureReason, List<ValidationError> errors) {
    }

    private final StudentProjectService studentProjectService;
    private final AgentProjectConfigProposalMapper proposalMapper;
    private final AgentProjectConfigRevisionMapper revisionMapper;
    private final AgentProjectConfigAuditEventMapper auditMapper;
    private final AgentProjectConfigExternalChangeMapper externalChangeMapper;
    private final AgentProjectConfigFileWriter fileWriter;
    private final AgentProjectConfigChangeEvidenceService evidenceService;

    public AgentProjectConfigApplyRecoveryService(StudentProjectService studentProjectService,
                                                  AgentProjectConfigProposalMapper proposalMapper,
                                                  AgentProjectConfigRevisionMapper revisionMapper,
                                                  AgentProjectConfigAuditEventMapper auditMapper,
                                                  AgentProjectConfigExternalChangeMapper externalChangeMapper,
                                                  AgentProjectConfigFileWriter fileWriter,
                                                  AgentProjectConfigChangeEvidenceService evidenceService) {
        this.studentProjectService = studentProjectService;
        this.proposalMapper = proposalMapper;
        this.revisionMapper = revisionMapper;
        this.auditMapper = auditMapper;
        this.externalChangeMapper = externalChangeMapper;
        this.fileWriter = fileWriter;
        this.evidenceService = evidenceService;
    }

    /**
     * Idempotently finishes an apply that is in flight or resumes it from any crash point.
     * Proposals that are not {@code applying} are returned unchanged; they are never decided
     * here.
     */
    public ApplyResult reconcile(Integer studentId, Integer projectId, Long proposalId) {
        StudentProject project = AgentProjectConfigOwnership.requireOwned(studentId, projectId,
                studentProjectService);
        AgentProjectConfigProposal proposal = ownedProposal(projectId, proposalId);
        if (!STATUS_APPLYING.equals(proposal.getStatus())) {
            return new ApplyResult(proposalId, proposal.getStatus(), proposal.getAppliedRevision(),
                    null, List.of());
        }
        ApplyResult staged = ensureStaged(project, proposal);
        if (isFailed(staged)) {
            return staged;
        }
        ApplyResult evidence = recordEvidence(project, proposal);
        if (isFailed(evidence)) {
            return evidence;
        }
        ApplyResult published = publish(project, proposal);
        if (isFailed(published)) {
            return published;
        }
        ApplyResult inserted = insertRevision(project, proposal);
        if (isFailed(inserted)) {
            return inserted;
        }
        return complete(project, proposal);
    }

    /** Crash point: staging verified and re-validated against the approved candidate digests. */
    ApplyResult ensureStaged(Integer studentId, Integer projectId, Long proposalId) {
        StudentProject project = AgentProjectConfigOwnership.requireOwned(studentId, projectId,
                studentProjectService);
        return ensureStaged(project, ownedProposal(projectId, proposalId));
    }

    /** Crash point: durable, secret-free evidence recorded (idempotent). */
    ApplyResult recordEvidence(Integer studentId, Integer projectId, Long proposalId) {
        StudentProject project = AgentProjectConfigOwnership.requireOwned(studentId, projectId,
                studentProjectService);
        return recordEvidence(project, ownedProposal(projectId, proposalId));
    }

    /** Crash point: staged tree published and verified (idempotent). */
    ApplyResult publish(Integer studentId, Integer projectId, Long proposalId) {
        StudentProject project = AgentProjectConfigOwnership.requireOwned(studentId, projectId,
                studentProjectService);
        return publish(project, ownedProposal(projectId, proposalId));
    }

    /** Crash point: next revision row inserted (idempotent). */
    ApplyResult insertRevision(Integer studentId, Integer projectId, Long proposalId) {
        StudentProject project = AgentProjectConfigOwnership.requireOwned(studentId, projectId,
                studentProjectService);
        return insertRevision(project, ownedProposal(projectId, proposalId));
    }

    /** Crash point: CAS {@code applying -> applied} with audit and staging cleanup. */
    ApplyResult complete(Integer studentId, Integer projectId, Long proposalId) {
        StudentProject project = AgentProjectConfigOwnership.requireOwned(studentId, projectId,
                studentProjectService);
        return complete(project, ownedProposal(projectId, proposalId));
    }

    /**
     * Appends one append-only audit event; the unique (project, idempotency key) constraint
     * makes repeated writes idempotent.
     */
    public void appendAudit(Integer studentId, Integer projectId, String eventType, String actor,
                            String reason, String previousStatus, String nextStatus,
                            String beforeDigest, String afterDigest, String changedPathSummary,
                            Long taskId, Long executionEpoch, String idempotencyKey) {
        AgentProjectConfigAuditEvent event = new AgentProjectConfigAuditEvent();
        event.setStudentId(studentId);
        event.setProjectId(projectId);
        event.setEventType(eventType);
        event.setActor(actor);
        event.setReason(reason);
        event.setPreviousStatus(previousStatus);
        event.setNextStatus(nextStatus);
        event.setBeforeDigest(beforeDigest);
        event.setAfterDigest(afterDigest);
        event.setChangedPathSummary(changedPathSummary);
        event.setTaskId(taskId);
        event.setExecutionEpoch(executionEpoch);
        event.setIdempotencyKey(idempotencyKey);
        try {
            auditMapper.insert(event);
        } catch (Exception duplicate) {
            // Idempotent append: the (project_id, idempotency_key) unique key already holds
            // the first durable event.
        }
    }

    /** Deterministic audit idempotency key for a proposal-scoped event. */
    public static String auditKey(Long proposalId, String eventType) {
        return "proposal-" + proposalId + "-" + eventType;
    }

    /** Audit event ids whose idempotency key starts with the given prefix (proposal-scoped). */
    public List<Long> auditEventIds(Integer studentId, Integer projectId, String keyPrefix) {
        AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return List.of();
        }
        return auditMapper.selectList(new QueryWrapper<AgentProjectConfigAuditEvent>()
                        .eq("project_id", projectId)
                        .likeRight("idempotency_key", keyPrefix)
                        .orderByAsc("event_id"))
                .stream().map(AgentProjectConfigAuditEvent::getEventId).toList();
    }

    /** Deterministic staging key derived from the proposal idempotency key. */
    public static String stageId(Integer projectId, String proposalKey) {
        String input = (projectId == null ? "" : projectId) + "|" + (proposalKey == null ? "" : proposalKey);
        return AgentProjectConfigCanonicalizer.sha256Hex(input).substring(0, 24);
    }

    private ApplyResult ensureStaged(StudentProject project, AgentProjectConfigProposal proposal) {
        if (!STATUS_APPLYING.equals(proposal.getStatus())) {
            return state(proposal);
        }
        Path stageDir = stagingDir(project, proposal);
        if (!Files.isDirectory(stageDir, LinkOption.NOFOLLOW_LINKS)) {
            return fail(project, proposal, FAILURE_STAGING_UNAVAILABLE, List.of());
        }
        TreeDigests.ScanResult staged = TreeDigests.scanStaging(stageDir);
        if (!staged.valid()) {
            return fail(project, proposal, FAILURE_VALIDATION, staged.errors());
        }
        String candidateTree = TreeDigests.treeDigestOfReference(proposal.getPatchReference());
        if (candidateTree == null || !candidateTree.equals(staged.treeDigest())
                || !proposal.getCandidateConfigDigest().equals(staged.configDigest())) {
            return fail(project, proposal, FAILURE_CANDIDATE_MISMATCH, List.of());
        }
        return state(proposal);
    }

    private ApplyResult recordEvidence(StudentProject project, AgentProjectConfigProposal proposal) {
        if (!STATUS_APPLYING.equals(proposal.getStatus())) {
            return state(proposal);
        }
        Path stageDir = stagingDir(project, proposal);
        TreeDigests.ScanResult staged = TreeDigests.scanStaging(stageDir);
        if (!staged.valid()) {
            return fail(project, proposal, FAILURE_VALIDATION, staged.errors());
        }
        AgentProjectConfigRevision head = latestRevision(project.getProjectId());
        String beforeDigest = head == null ? null : TreeDigests.treeDigestOf(head);
        List<String> changed = TreeDigests.changedPaths(TreeDigests.headFiles(head), staged.fileHashes());
        evidenceService.recordEvidence(project.getStudentId(), project.getProjectId(),
                proposal.getProposalId(), proposal.getBaseRevision(), beforeDigest,
                staged.treeDigest(), changed);
        return state(proposal);
    }

    private ApplyResult publish(StudentProject project, AgentProjectConfigProposal proposal) {
        if (!STATUS_APPLYING.equals(proposal.getStatus())) {
            return state(proposal);
        }
        String candidateTree = TreeDigests.treeDigestOfReference(proposal.getPatchReference());
        if (candidateTree == null) {
            return fail(project, proposal, FAILURE_CANDIDATE_MISMATCH, List.of());
        }
        Path projectRoot = projectRoot(project);
        TreeDigests.ScanResult published = TreeDigests.scanProtected(projectRoot);
        if (published.treeDigest() != null && published.treeDigest().equals(candidateTree)) {
            return state(proposal);
        }
        try {
            return fileWriter.withProjectLock(projectRoot, () -> {
                if (!isCurrentHead(project, proposal.getBaseRevision())) {
                    return fail(project, proposal, FAILURE_CONFLICT, List.of());
                }
                Path stageDir = stagingDir(project, proposal);
                TreeDigests.ScanResult staged = TreeDigests.scanStaging(stageDir);
                if (!staged.valid()) {
                    return fail(project, proposal, FAILURE_VALIDATION, staged.errors());
                }
                String stageId = stageId(project.getProjectId(), proposal.getProposalKey());
                fileWriter.publishStaged(projectRoot, stageId,
                        new ArrayList<>(staged.fileHashes().keySet()));
                Map<String, String> head = TreeDigests.headFiles(latestRevision(project.getProjectId()));
                for (String obsolete : head.keySet()) {
                    if (!staged.fileHashes().containsKey(obsolete)) {
                        fileWriter.delete(projectRoot, obsolete);
                    }
                }
                TreeDigests.ScanResult after = TreeDigests.scanProtected(projectRoot);
                if (after.treeDigest() == null || !after.treeDigest().equals(candidateTree)) {
                    return fail(project, proposal, FAILURE_VERIFICATION, after.errors());
                }
                return state(proposal);
            });
        } catch (IOException | PathViolationException failure) {
            return fail(project, proposal, FAILURE_VERIFICATION, List.of());
        }
    }

    private ApplyResult insertRevision(StudentProject project, AgentProjectConfigProposal proposal) {
        if (!STATUS_APPLYING.equals(proposal.getStatus())) {
            return state(proposal);
        }
        Integer projectId = project.getProjectId();
        Long expectedNext = proposal.getBaseRevision() + 1;
        Path projectRoot = projectRoot(project);
        try {
            return fileWriter.withProjectLock(projectRoot, () -> {
                AgentProjectConfigRevision latest = latestRevision(projectId);
                if (latest != null && latest.getRevision() >= expectedNext) {
                    if (matchesProposal(latest, expectedNext, proposal)) {
                        return state(proposal);
                    }
                    return fail(project, proposal, FAILURE_CONFLICT, List.of());
                }
                if (latest == null || !latest.getRevision().equals(expectedNext - 1)) {
                    return fail(project, proposal, FAILURE_CONFLICT, List.of());
                }
                // Cross-step fence: the published tree must still be byte-for-byte the
                // approved candidate before a revision row may reference it. A concurrent
                // apply may have republished the tree after our publish step; on a mismatch
                // this apply fails typed and never inserts a revision the disk no longer
                // holds, so the accepted head always matches the tree it references.
                TreeDigests.ScanResult published = TreeDigests.scanProtected(projectRoot);
                if (published.treeDigest() == null || !published.treeDigest().equals(
                        TreeDigests.treeDigestOfReference(proposal.getPatchReference()))) {
                    return fail(project, proposal, FAILURE_VERIFICATION, published.errors());
                }
                Path stageDir = stagingDir(project, proposal);
                TreeDigests.ScanResult staged = TreeDigests.scanStaging(stageDir);
                if (!staged.valid()) {
                    return fail(project, proposal, FAILURE_VALIDATION, staged.errors());
                }
                JsonObject envelope = new JsonObject();
                envelope.addProperty("document", staged.canonicalDocumentJson());
                envelope.addProperty("treeDigest", staged.treeDigest());
                JsonObject files = new JsonObject();
                staged.fileHashes().forEach(files::addProperty);
                envelope.add("files", files);

                AgentProjectConfigRevision revision = new AgentProjectConfigRevision();
                revision.setStudentId(project.getStudentId());
                revision.setProjectId(projectId);
                revision.setRevision(expectedNext);
                revision.setConfigDigest(proposal.getCandidateConfigDigest());
                revision.setTreeReference(proposal.getPatchReference());
                revision.setSchemaVersion(staged.schemaVersion());
                revision.setNormalizedConfig(envelope.toString());
                revision.setValidationStatus(AgentProjectConfigRevisionService.VALIDATION_VALID);
                revision.setSourceActor(proposal.getDecisionActor());
                try {
                    revisionMapper.insert(revision);
                } catch (Exception raced) {
                    AgentProjectConfigRevision existing = latestRevision(projectId);
                    if (matchesProposal(existing, expectedNext, proposal)) {
                        return state(proposal);
                    }
                    return fail(project, proposal, FAILURE_CONFLICT, List.of());
                }
                return state(proposal);
            });
        } catch (IOException | PathViolationException failure) {
            return fail(project, proposal, FAILURE_VERIFICATION, List.of());
        }
    }

    /**
     * Whether the revision row is exactly the durable head this proposal would produce: same
     * revision number, same canonical config digest AND same tree reference. The tree
     * reference comparison is mandatory — two candidates can share a canonical config digest
     * while their complete trees differ (for example a proposal that omits an undeclared
     * file), and only a full tree match may take the idempotent shortcut.
     */
    private boolean matchesProposal(AgentProjectConfigRevision revision, Long expectedNext,
                                    AgentProjectConfigProposal proposal) {
        if (revision == null || !revision.getRevision().equals(expectedNext)) {
            return false;
        }
        if (!proposal.getCandidateConfigDigest().equals(revision.getConfigDigest())) {
            return false;
        }
        String candidateTree = TreeDigests.treeDigestOfReference(proposal.getPatchReference());
        String latestTree = TreeDigests.treeDigestOfReference(revision.getTreeReference());
        return candidateTree != null && candidateTree.equals(latestTree);
    }

    private ApplyResult complete(StudentProject project, AgentProjectConfigProposal proposal) {
        if (STATUS_APPLIED.equals(proposal.getStatus())) {
            return state(proposal);
        }
        Integer projectId = project.getProjectId();
        Long expectedNext = proposal.getBaseRevision() + 1;
        int updated = proposalMapper.update(null, new UpdateWrapper<AgentProjectConfigProposal>()
                .eq("proposal_id", proposal.getProposalId())
                .eq("status", STATUS_APPLYING)
                .set("status", STATUS_APPLIED)
                .set("applied_revision", expectedNext));
        if (updated > 0) {
            AgentProjectConfigRevision base = revisionByNumber(projectId, proposal.getBaseRevision());
            appendAudit(project.getStudentId(), projectId, "applied",
                    proposal.getDecisionActor(), null, STATUS_APPLYING, STATUS_APPLIED,
                    base == null ? null : base.getConfigDigest(),
                    proposal.getCandidateConfigDigest(), proposal.getChangedPathSummary(),
                    proposal.getOriginTaskId(), proposal.getOriginExecutionEpoch(),
                    auditKey(proposal.getProposalId(), "applied"));
            String candidateTree = TreeDigests.treeDigestOfReference(proposal.getPatchReference());
            externalChangeMapper.update(null, new UpdateWrapper<AgentProjectConfigExternalChange>()
                    .eq("student_id", project.getStudentId())
                    .eq("project_id", projectId)
                    .eq("base_revision", proposal.getBaseRevision())
                    .eq("observed_tree_digest", candidateTree)
                    .eq("status", AgentProjectConfigExternalChangeService.STATUS_PENDING)
                    .set("status", AgentProjectConfigExternalChangeService.STATUS_APPLIED));
        }
        cleanupStagingQuietly(project, proposal);
        return new ApplyResult(proposal.getProposalId(), STATUS_APPLIED, expectedNext, null, List.of());
    }

    private ApplyResult fail(StudentProject project, AgentProjectConfigProposal proposal,
                             String reason, List<ValidationError> errors) {
        int updated = proposalMapper.update(null, new UpdateWrapper<AgentProjectConfigProposal>()
                .eq("proposal_id", proposal.getProposalId())
                .eq("status", STATUS_APPLYING)
                .set("status", STATUS_FAILED));
        if (updated > 0) {
            appendAudit(project.getStudentId(), project.getProjectId(), "failed",
                    proposal.getDecisionActor(), reason, STATUS_APPLYING, STATUS_FAILED,
                    null, proposal.getCandidateConfigDigest(), proposal.getChangedPathSummary(),
                    proposal.getOriginTaskId(), proposal.getOriginExecutionEpoch(),
                    auditKey(proposal.getProposalId(), "failed"));
        }
        cleanupStagingQuietly(project, proposal);
        return new ApplyResult(proposal.getProposalId(), STATUS_FAILED, null, reason,
                errors == null ? List.of() : List.copyOf(errors));
    }

    private ApplyResult state(AgentProjectConfigProposal proposal) {
        return new ApplyResult(proposal.getProposalId(), proposal.getStatus(),
                proposal.getAppliedRevision(), null, List.of());
    }

    private boolean isFailed(ApplyResult result) {
        return STATUS_FAILED.equals(result.status());
    }

    private AgentProjectConfigProposal ownedProposal(Integer projectId, Long proposalId) {
        AgentProjectConfigProposal proposal = proposalMapper.selectOne(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", projectId)
                .eq("proposal_id", proposalId)
                .last("LIMIT 1"));
        if (proposal == null) {
            throw new AgentProjectConfigOwnership.ProjectConfigNotFoundException();
        }
        return proposal;
    }

    private AgentProjectConfigRevision latestRevision(Integer projectId) {
        return revisionMapper.selectOne(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", projectId)
                .orderByDesc("revision")
                .last("LIMIT 1"));
    }

    private AgentProjectConfigRevision revisionByNumber(Integer projectId, Long revision) {
        return revisionMapper.selectOne(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", projectId)
                .eq("revision", revision)
                .last("LIMIT 1"));
    }

    private boolean isCurrentHead(StudentProject project, Long baseRevision) {
        AgentProjectConfigRevision latest = latestRevision(project.getProjectId());
        return latest != null && baseRevision != null && baseRevision.equals(latest.getRevision());
    }

    private Path projectRoot(StudentProject project) {
        return Path.of(project.getWorkspacePath());
    }

    private Path stagingDir(StudentProject project, AgentProjectConfigProposal proposal) {
        String stageId = stageId(project.getProjectId(), proposal.getProposalKey());
        return projectRoot(project).resolve(AgentProjectConfigFileWriter.STAGING_RELATIVE_DIR)
                .resolve(stageId).normalize();
    }

    private void cleanupStagingQuietly(StudentProject project, AgentProjectConfigProposal proposal) {
        try {
            fileWriter.cleanupStaging(projectRoot(project),
                    stageId(project.getProjectId(), proposal.getProposalKey()));
        } catch (IOException ignored) {
            // Staging leftovers are inert (outside the protected tree); a later apply retry
            // or the next proposal with the same idempotency key overwrites them.
        }
    }

    /**
     * Deterministic canonical tree/document machinery shared by proposal creation and apply.
     * The canonical content rules mirror {@link AgentProjectConfigRevisionService} exactly so
     * that a published apply always produces the digest the revision reader computes on the
     * next load.
     */
    static final class TreeDigests {

        static final String MANIFEST = AgentProjectConfigValidator.MANIFEST;

        /** Staging reads are bounded by the same per-file ceiling as the protected tree. */
        static final long MAX_STAGING_FILE_BYTES = ProtectedProjectConfigPath.DEFAULT_MAX_FILE_BYTES;

        /** Canonical scan of one configuration tree (candidate map, staging dir or protected tree). */
        record ScanResult(Map<String, String> fileHashes, Map<String, String> canonicalTexts,
                          String treeDigest, String configDigest, String canonicalDocumentJson,
                          String schemaVersion, List<ValidationError> errors) {
            boolean valid() {
                return errors.isEmpty() && treeDigest != null;
            }
        }

        private TreeDigests() {
        }

        static ScanResult scanCandidate(Map<String, String> files) {
            List<ValidationError> errors = new ArrayList<>();
            if (files == null || files.isEmpty()) {
                errors.add(new ValidationError(MANIFEST, "$",
                        AgentProjectConfigValidator.REASON_MISSING_REQUIRED_KEY,
                        "a complete candidate document is required"));
                return invalid(errors);
            }
            return scanFiles(files, errors);
        }

        static ScanResult scanStaging(Path stagingDir) {
            Map<String, String> files = new TreeMap<>();
            List<ValidationError> errors = new ArrayList<>();
            if (!Files.isDirectory(stagingDir, LinkOption.NOFOLLOW_LINKS)) {
                return invalid(List.of(new ValidationError(".", "",
                        AgentProjectConfigValidator.REASON_PATH_MISSING,
                        "staging tree does not exist")));
            }
            walkCollect(stagingDir, "", stagingDir, files, errors);
            if (!errors.isEmpty()) {
                return invalid(errors);
            }
            if (files.isEmpty()) {
                return invalid(List.of(new ValidationError(".", "",
                        AgentProjectConfigValidator.REASON_PATH_MISSING,
                        "staging tree is empty")));
            }
            return scanFiles(files, errors);
        }

        static ScanResult scanProtected(Path projectRoot) {
            Map<String, String> files = new TreeMap<>();
            List<ValidationError> errors = new ArrayList<>();
            ProtectedProjectConfigPath boundary;
            try {
                boundary = new ProtectedProjectConfigPath(projectRoot);
            } catch (PathViolationException e) {
                return invalid(List.of(new ValidationError(e.relativePath(), "",
                        e.reasonCode(), e.getMessage())));
            }
            walkProtected(boundary.configDir(), "", boundary, files, errors);
            if (!errors.isEmpty()) {
                return invalid(errors);
            }
            if (files.isEmpty()) {
                return invalid(List.of(new ValidationError(MANIFEST, "",
                        AgentProjectConfigValidator.REASON_PATH_MISSING,
                        "config tree is empty")));
            }
            return scanFiles(files, errors);
        }

        /**
         * Reads every file of the protected tree as raw UTF-8 text (path -> content). Used by
         * external-change materialization so the candidate is the complete observed tree,
         * including undeclared files, which the apply then never discards.
         */
        static Map<String, String> readRawTree(Path projectRoot) {
            Map<String, String> files = new TreeMap<>();
            ProtectedProjectConfigPath boundary;
            try {
                boundary = new ProtectedProjectConfigPath(projectRoot);
            } catch (PathViolationException e) {
                return Map.of();
            }
            walkProtectedRaw(boundary.configDir(), "", boundary, files);
            return files;
        }

        private static void walkProtectedRaw(Path dir, String prefix, ProtectedProjectConfigPath boundary,
                                             Map<String, String> files) {
            try {
                BasicFileAttributes dirAttributes = Files.readAttributes(
                        dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (dirAttributes.isSymbolicLink() || dirAttributes.isOther()) {
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
                        walkProtectedRaw(entry, relative, boundary, files);
                    } else if (attributes.isRegularFile()) {
                        try {
                            files.put(relative, boundary.readText(relative));
                        } catch (PathViolationException e) {
                            files.clear();
                            return;
                        }
                    } else {
                        files.clear();
                        return;
                    }
                }
            } catch (IOException e) {
                files.clear();
            }
        }

        static List<String> changedPaths(Map<String, String> before, Map<String, String> after) {
            Set<String> paths = new TreeSet<>(before.keySet());
            paths.addAll(after.keySet());
            List<String> changed = new ArrayList<>();
            for (String path : paths) {
                if (!Objects.equals(before.get(path), after.get(path))) {
                    changed.add(path);
                }
            }
            return List.copyOf(changed);
        }

        static Map<String, String> headFiles(AgentProjectConfigRevision revision) {
            if (revision == null || revision.getNormalizedConfig() == null) {
                return Map.of();
            }
            try {
                JsonObject envelope = JsonParser.parseString(revision.getNormalizedConfig()).getAsJsonObject();
                if (!envelope.has("files") || !envelope.get("files").isJsonObject()) {
                    return Map.of();
                }
                TreeMap<String, String> files = new TreeMap<>();
                envelope.getAsJsonObject("files").entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonPrimitive()) {
                        files.put(entry.getKey(), entry.getValue().getAsString());
                    }
                });
                return files;
            } catch (RuntimeException malformed) {
                return Map.of();
            }
        }

        static String treeDigestOf(AgentProjectConfigRevision revision) {
            return revision == null ? null : treeDigestOfReference(revision.getTreeReference());
        }

        static String treeDigestOfReference(String treeReference) {
            if (treeReference == null || !treeReference.startsWith(
                    AgentProjectConfigRevisionService.TREE_REFERENCE_PREFIX)) {
                return null;
            }
            return treeReference.substring(AgentProjectConfigRevisionService.TREE_REFERENCE_PREFIX.length());
        }

        private static ScanResult scanFiles(Map<String, String> files, List<ValidationError> errors) {
            String manifestText = files.get(MANIFEST);
            if (manifestText == null) {
                errors.add(new ValidationError(MANIFEST, "$",
                        AgentProjectConfigValidator.REASON_MISSING_REQUIRED_KEY,
                        "config manifest agent.json is missing"));
                return invalid(errors);
            }
            JsonObject manifest = parseObject(MANIFEST, manifestText, errors);
            if (manifest == null) {
                return invalid(errors);
            }
            errors.addAll(new AgentProjectConfigValidator().validateManifest(manifest));
            if (!errors.isEmpty()) {
                return invalid(errors);
            }
            List<LoadedChild> children = buildChildren(manifest, files, errors);
            if (!errors.isEmpty()) {
                return invalid(errors);
            }
            errors.addAll(new AgentProjectConfigValidator().validate(MANIFEST, manifest, children));
            if (!errors.isEmpty()) {
                return invalid(errors);
            }

            TreeMap<String, String> canonicalTexts = new TreeMap<>();
            TreeMap<String, String> fileHashes = new TreeMap<>();
            canonicalTexts.put(MANIFEST, AgentProjectConfigCanonicalizer.canonicalJson(manifest));
            for (LoadedChild child : children) {
                if (child.json() != null) {
                    canonicalTexts.put(child.refPath(), AgentProjectConfigCanonicalizer.canonicalJson(child.json()));
                } else {
                    canonicalTexts.put(child.refPath(), child.text());
                }
            }
            for (Map.Entry<String, String> entry : files.entrySet()) {
                if (!canonicalTexts.containsKey(entry.getKey())) {
                    canonicalTexts.put(entry.getKey(),
                            AgentProjectConfigCanonicalizer.normalizeLineEndings(entry.getValue()).strip());
                }
            }
            for (Map.Entry<String, String> entry : canonicalTexts.entrySet()) {
                fileHashes.put(entry.getKey(), AgentProjectConfigCanonicalizer.sha256Hex(entry.getValue()));
            }
            TreeMap<String, String> childTexts = new TreeMap<>();
            canonicalTexts.forEach((path, text) -> {
                if (!MANIFEST.equals(path)) {
                    childTexts.put(path, text);
                }
            });
            String canonicalDocument = AgentProjectConfigCanonicalizer.canonicalDocument(
                    canonicalTexts.get(MANIFEST), childTexts);
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, String> entry : fileHashes.entrySet()) {
                builder.append(entry.getKey()).append('\n');
                builder.append(entry.getValue()).append('\n');
            }
            String schemaVersion = manifest.has("schemaVersion") && manifest.get("schemaVersion").isJsonPrimitive()
                    ? manifest.get("schemaVersion").getAsString() : null;
            return new ScanResult(Map.copyOf(fileHashes), Map.copyOf(canonicalTexts),
                    AgentProjectConfigCanonicalizer.sha256Hex(builder.toString()),
                    AgentProjectConfigCanonicalizer.sha256Hex(canonicalDocument),
                    canonicalDocument, schemaVersion, List.of());
        }

        private static List<LoadedChild> buildChildren(JsonObject manifest, Map<String, String> files,
                                                       List<ValidationError> errors) {
            List<LoadedChild> children = new ArrayList<>();
            Map<String, String> seenRefs = new HashMap<>();
            collectRefArray(manifest, files, "agents", ChildKind.AGENT, children, seenRefs, errors);
            collectRefArray(manifest, files, "tools", ChildKind.TOOL, children, seenRefs, errors);
            collectRefArray(manifest, files, "mcpServers", ChildKind.MCP, children, seenRefs, errors);
            collectRefArray(manifest, files, "skills", ChildKind.SKILL, children, seenRefs, errors);
            if (manifest.has("environment") && manifest.get("environment").isJsonPrimitive()
                    && manifest.get("environment").getAsJsonPrimitive().isString()
                    && "environment.json".equals(manifest.get("environment").getAsString())) {
                loadChild(manifest.get("environment").getAsString(), "$.environment", ChildKind.ENVIRONMENT,
                        files, children, seenRefs, errors);
            }
            return children;
        }

        private static void collectRefArray(JsonObject manifest, Map<String, String> files, String key,
                                            ChildKind kind, List<LoadedChild> out,
                                            Map<String, String> seenRefs, List<ValidationError> errors) {
            if (!manifest.has(key) || !manifest.get(key).isJsonArray()) {
                return;
            }
            var array = manifest.getAsJsonArray(key);
            for (int index = 0; index < array.size(); index++) {
                if (array.get(index).isJsonPrimitive()
                        && array.get(index).getAsJsonPrimitive().isString()) {
                    loadChild(array.get(index).getAsString(), "$." + key + "[" + index + "]",
                            kind, files, out, seenRefs, errors);
                }
            }
        }

        private static void loadChild(String ref, String jsonPath, ChildKind kind, Map<String, String> files,
                                      List<LoadedChild> out, Map<String, String> seenRefs,
                                      List<ValidationError> errors) {
            String normalizedRef = ref.replace('\\', '/');
            if (seenRefs.containsKey(normalizedRef)) {
                errors.add(new ValidationError(normalizedRef, jsonPath,
                        AgentProjectConfigValidator.REASON_DUPLICATE_REFERENCE,
                        "duplicate child file reference"));
                return;
            }
            seenRefs.put(normalizedRef, jsonPath);
            if (!files.containsKey(normalizedRef)) {
                errors.add(new ValidationError(normalizedRef, jsonPath,
                        AgentProjectConfigValidator.REASON_REFERENCE_UNRESOLVABLE,
                        "declared child file is missing"));
                return;
            }
            if (kind == ChildKind.SKILL) {
                out.add(LoadedChild.text(normalizedRef,
                        AgentProjectConfigCanonicalizer.normalizeLineEndings(files.get(normalizedRef)).strip()));
                return;
            }
            JsonObject doc = parseObject(normalizedRef, files.get(normalizedRef), errors);
            if (doc != null) {
                out.add(LoadedChild.json(normalizedRef, kind, doc));
            }
        }

        private static JsonObject parseObject(String filePath, String text, List<ValidationError> errors) {
            try {
                var element = JsonParser.parseString(text);
                if (!element.isJsonObject()) {
                    errors.add(new ValidationError(filePath, "$",
                            AgentProjectConfigValidator.REASON_MALFORMED_JSON,
                            "expected a JSON object"));
                    return null;
                }
                return element.getAsJsonObject();
            } catch (RuntimeException e) {
                errors.add(new ValidationError(filePath, "$",
                        AgentProjectConfigValidator.REASON_MALFORMED_JSON,
                        "config file is not valid JSON"));
                return null;
            }
        }

        private static void walkCollect(Path dir, String prefix, Path stagingRoot,
                                        Map<String, String> files, List<ValidationError> errors) {
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
                        walkCollect(entry, relative, stagingRoot, files, errors);
                    } else if (attributes.isRegularFile()) {
                        Path resolved = stagingRoot.resolve(relative).normalize();
                        if (!resolved.startsWith(stagingRoot)) {
                            errors.add(new ValidationError(relative, "",
                                    AgentProjectConfigValidator.REASON_PATH_ESCAPE,
                                    "staging path escapes the staging tree"));
                            continue;
                        }
                        if (attributes.size() > MAX_STAGING_FILE_BYTES) {
                            errors.add(new ValidationError(relative, "",
                                    AgentProjectConfigValidator.REASON_PATH_SIZE_LIMIT,
                                    "staging file exceeds the configured size limit"));
                            continue;
                        }
                        files.put(relative, Files.readString(resolved, StandardCharsets.UTF_8));
                    } else {
                        errors.add(new ValidationError(relative, "",
                                AgentProjectConfigValidator.REASON_PATH_SYMLINK,
                                "symbolic link or reparse point is not allowed"));
                    }
                }
            } catch (IOException e) {
                errors.add(new ValidationError(prefix.isEmpty() ? "." : prefix, "",
                        AgentProjectConfigValidator.REASON_PATH_INVALID,
                        "staging tree cannot be scanned"));
            }
        }

        private static void walkProtected(Path dir, String prefix, ProtectedProjectConfigPath boundary,
                                          Map<String, String> files, List<ValidationError> errors) {
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
                        walkProtected(entry, relative, boundary, files, errors);
                    } else if (attributes.isRegularFile()) {
                        try {
                            files.put(relative, boundary.readText(relative));
                        } catch (PathViolationException e) {
                            errors.add(new ValidationError(e.relativePath(), "", e.reasonCode(),
                                    e.getMessage()));
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

        private static ScanResult invalid(List<ValidationError> errors) {
            return new ScanResult(Map.of(), Map.of(), null, null, null, null, List.copyOf(errors));
        }
    }
}
