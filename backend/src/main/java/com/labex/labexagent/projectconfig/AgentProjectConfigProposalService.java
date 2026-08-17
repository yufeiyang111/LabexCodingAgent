package com.labex.labexagent.projectconfig;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentProjectConfigProposal;
import com.labex.entity.AgentProjectConfigRevision;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigApplyRecoveryService.ApplyResult;
import com.labex.labexagent.projectconfig.AgentProjectConfigApplyRecoveryService.TreeDigests;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership.ProjectConfigNotFoundException;
import com.labex.labexagent.projectconfig.AgentProjectConfigValidator.ValidationError;
import com.labex.labexagent.projectconfig.ProtectedProjectConfigPath.PathViolationException;
import com.labex.labexagent.run.AgentRunContinuationRequestFactory;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunLifecycleService.InteractionClaimOutcome;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.mapper.AgentProjectConfigProposalMapper;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Owner-only proposal authority for project configuration changes.
 *
 * <p>Creation accepts a complete candidate document (path -> content) plus an expected
 * revision and an idempotency key. The candidate is path-validated, schema/reference/
 * capability-validated, digested with the same canonical machinery as the revision reader,
 * and durably staged beneath {@code .labex-agent/.config-staging/} (outside the protected
 * tree) before the {@code pending} row is inserted; the staged tree is the only durable
 * source for the later apply, so a crash never loses the approved candidate. Repeated
 * proposal keys return the first durable proposal.
 *
 * <p>The decision endpoint is a CAS state machine: repeated identical decision idempotency
 * keys return the first durable result, expiry CASes {@code pending -> expired} with a real
 * HTTP 410, a stale base revision or expected revision CASes {@code pending -> stale} with a
 * real HTTP 409, and {@code approve} CASes {@code pending -> applying} before handing over to
 * {@link AgentProjectConfigApplyRecoveryService}, whose typed failure (conflict, validation,
 * staging, verification) is surfaced as a structured 4xx with the proposal durably marked
 * {@code failed} — never a false applied head.
 *
 * <p>External-change observations from {@link AgentProjectConfigExternalChangeService} are
 * materialized into {@code external_change} proposals here, through the same ownership and
 * CAS authority; the revision reader never writes proposals.
 */
@Component
public class AgentProjectConfigProposalService {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPLYING = "applying";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_STALE = "stale";
    public static final String STATUS_FAILED = "failed";

    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_EXTERNAL_CHANGE = "external_change";

    /** Normal proposal expiry; the DDL caps expiry at one minute after creation. */
    public static final long EXPIRY_SECONDS = 60;

    /** Decision idempotency keys are capped by the {@code decision_idempotency_key} column. */
    public static final int DECISION_KEY_MAX_LENGTH = 128;

    /** 决策后 claim 交互 dispatch 的 DEFERRED 重试上限；租约最长 30s，这里最多等待约 12s。 */
    private static final int MAX_CLAIM_RETRIES = 60;
    private static final long CLAIM_RETRY_DELAY_MS = 200L;

    public static final String EVENT_PROPOSED = "proposed";
    public static final String EVENT_APPROVED = "approved";
    public static final String EVENT_REJECTED = "rejected";
    public static final String EVENT_EXPIRED = "expired";
    public static final String EVENT_CONFLICTED = "conflicted";
    public static final String EVENT_EXTERNAL_CHANGE_DETECTED = "external-change-detected";

    private static final Logger log = LoggerFactory.getLogger(AgentProjectConfigProposalService.class);

    /** Creation request: complete candidate document, expected revision, reason and key. */
    public record CreateProposalRequest(Long expectedRevision, Map<String, String> candidate,
                                        String patch, String reason, String idempotencyKey,
                                        String source, Long externalChangeId,
                                        Long originTaskId, Long originExecutionEpoch,
                                        String originToolCallId) {
        public static CreateProposalRequest of(Long expectedRevision, Map<String, String> candidate,
                                               String reason, String idempotencyKey) {
            return new CreateProposalRequest(expectedRevision, candidate, null, reason,
                    idempotencyKey, null, null, null, null, null);
        }
    }

    /** Decision request: only {@code approve} or {@code reject}, freshness guard and key. */
    public record DecisionRequest(String decision, Long expectedRevision, String decisionIdempotencyKey) {
        public static DecisionRequest approve(Long expectedRevision, String key) {
            return new DecisionRequest("approve", expectedRevision, key);
        }

        public static DecisionRequest reject(Long expectedRevision, String key) {
            return new DecisionRequest("reject", expectedRevision, key);
        }

        public static DecisionRequest of(String decision, Long expectedRevision, String key) {
            return new DecisionRequest(decision, expectedRevision, key);
        }
    }

    /** Redacted creation result. */
    public record ProposalResult(Long proposalId, String status, Long baseRevision,
                                 String candidateConfigDigest, String changedPathSummary,
                                 LocalDateTime expiresTime) {
    }

    /** Redacted list projection. */
    public record ProposalSummary(Long proposalId, String status, Long baseRevision,
                                  String candidateConfigDigest, String changedPathSummary,
                                  String reason, String source, String creator,
                                  LocalDateTime expiresTime, LocalDateTime decisionTime,
                                  Long appliedRevision) {
    }

    /** Redacted detail projection with audit references. */
    public record ProposalDetail(Long proposalId, String status, Long baseRevision,
                                 String candidateConfigDigest, String patchReference,
                                 String changedPathSummary, String reason, String source,
                                 String creator, Long originTaskId, Long originExecutionEpoch,
                                 String originToolCallId, LocalDateTime expiresTime,
                                 LocalDateTime createTime, LocalDateTime decisionTime,
                                 Long appliedRevision, List<Long> auditEventIds) {
    }

    /** Durable decision outcome; interactionId/resumeEpoch 只在该决策恢复了任务时非空。 */
    public record DecisionResult(Long proposalId, String status, Long appliedRevision,
                                 LocalDateTime decisionTime, String interactionId, Long resumeEpoch) {
        public DecisionResult(Long proposalId, String status, Long appliedRevision,
                              LocalDateTime decisionTime) {
            this(proposalId, status, appliedRevision, decisionTime, null, null);
        }
    }

    /** Typed, redaction-safe proposal failure carrying a real HTTP status. */
    public static final class ProposalException extends RuntimeException {
        private final int httpStatus;
        private final String reasonCode;
        private final List<ValidationError> errors;

        public ProposalException(int httpStatus, String reasonCode, String message) {
            this(httpStatus, reasonCode, message, List.of());
        }

        public ProposalException(int httpStatus, String reasonCode, String message,
                                 List<ValidationError> errors) {
            super(message);
            this.httpStatus = httpStatus;
            this.reasonCode = reasonCode;
            this.errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public List<ValidationError> errors() {
            return errors;
        }
    }

    private final StudentProjectService studentProjectService;
    private final AgentProjectConfigProposalMapper proposalMapper;
    private final AgentProjectConfigRevisionMapper revisionMapper;
    private final AgentProjectConfigFileWriter fileWriter;
    private final AgentProjectConfigApplyRecoveryService recoveryService;
    private final AgentProjectConfigExternalChangeService externalChangeService;
    private final AgentRunInteractionService interactionService;
    private final AgentRunLifecycleService lifecycleService;
    private final AgentTaskService taskService;
    private final AgentLoopEngine loopEngine;

    @Autowired
    public AgentProjectConfigProposalService(StudentProjectService studentProjectService,
                                             AgentProjectConfigProposalMapper proposalMapper,
                                             AgentProjectConfigRevisionMapper revisionMapper,
                                             AgentProjectConfigFileWriter fileWriter,
                                             AgentProjectConfigApplyRecoveryService recoveryService,
                                             AgentProjectConfigExternalChangeService externalChangeService,
                                             AgentRunInteractionService interactionService,
                                             AgentRunLifecycleService lifecycleService,
                                             AgentTaskService taskService,
                                             @Lazy AgentLoopEngine loopEngine) {
        this.studentProjectService = studentProjectService;
        this.proposalMapper = proposalMapper;
        this.revisionMapper = revisionMapper;
        this.fileWriter = fileWriter;
        this.recoveryService = recoveryService;
        this.externalChangeService = externalChangeService;
        this.interactionService = interactionService;
        this.lifecycleService = lifecycleService;
        this.taskService = taskService;
        this.loopEngine = loopEngine;
    }

    /** 兼容构造：不携带任务恢复依赖时，decision 只做 proposal 权威 CAS，不产生任务投影。 */
    public AgentProjectConfigProposalService(StudentProjectService studentProjectService,
                                             AgentProjectConfigProposalMapper proposalMapper,
                                             AgentProjectConfigRevisionMapper revisionMapper,
                                             AgentProjectConfigFileWriter fileWriter,
                                             AgentProjectConfigApplyRecoveryService recoveryService,
                                             AgentProjectConfigExternalChangeService externalChangeService) {
        this(studentProjectService, proposalMapper, revisionMapper, fileWriter, recoveryService,
                externalChangeService, null, null, null, null);
    }

    /**
     * Creates one pending proposal for the authenticated owner's project. The candidate is
     * validated and staged before the row is inserted; a duplicate idempotency key returns
     * the first durable proposal without a second row.
     */
    public ProposalResult createProposal(Integer studentId, Integer projectId,
                                         CreateProposalRequest request) {
        StudentProject project = owned(studentId, projectId);
        if (request == null || request.candidate() == null || request.candidate().isEmpty()) {
            throw new ProposalException(400, "INVALID_CANDIDATE",
                    "a complete candidate document is required");
        }
        String key = normalizedKey(request.idempotencyKey(), 192, "idempotencyKey");
        AgentProjectConfigProposal existing = findByProposalKey(projectId, key);
        if (existing != null) {
            return toProposalResult(existing);
        }
        AgentProjectConfigRevision head = currentRevision(projectId);
        if (head == null) {
            throw new ProposalException(409, "NO_ACCEPTED_REVISION",
                    "No accepted configuration revision exists yet");
        }
        if (request.expectedRevision() != null && !request.expectedRevision().equals(head.getRevision())) {
            throw new ProposalException(409, "STALE_REVISION",
                    "Stale revision; reload the project configuration");
        }
        Path projectRoot = Path.of(project.getWorkspacePath());
        for (String path : request.candidate().keySet()) {
            try {
                new ProtectedProjectConfigPath(projectRoot).resolve(path);
            } catch (PathViolationException e) {
                throw new ProposalException(400, "INVALID_CANDIDATE", "invalid candidate path",
                        List.of(new ValidationError(e.relativePath(), "", e.reasonCode(), e.getMessage())));
            }
        }
        TreeDigests.ScanResult scan = TreeDigests.scanCandidate(request.candidate());
        if (!scan.valid()) {
            throw new ProposalException(400, "INVALID_CANDIDATE",
                    "candidate document is invalid", scan.errors());
        }
        List<String> changedPaths = TreeDigests.changedPaths(TreeDigests.headFiles(head), scan.fileHashes());
        String stageId = AgentProjectConfigApplyRecoveryService.stageId(projectId, key);
        try {
            for (Map.Entry<String, String> entry : request.candidate().entrySet()) {
                fileWriter.stage(projectRoot, stageId, entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            throw new ProposalException(500, "STAGING_FAILED", "unable to stage the candidate");
        } catch (PathViolationException e) {
            throw new ProposalException(400, "INVALID_CANDIDATE", "invalid candidate path");
        }

        AgentProjectConfigProposal proposal = new AgentProjectConfigProposal();
        proposal.setStudentId(studentId);
        proposal.setProjectId(projectId);
        proposal.setProposalKey(key);
        proposal.setBaseRevision(head.getRevision());
        proposal.setCandidateConfigDigest(scan.configDigest());
        proposal.setPatchReference(AgentProjectConfigRevisionService.TREE_REFERENCE_PREFIX + scan.treeDigest());
        proposal.setChangedPathSummary(AgentProjectConfigExternalChangeService.summarize(changedPaths));
        proposal.setReason(trim(request.reason(), 2048));
        proposal.setOriginTaskId(request.originTaskId());
        proposal.setOriginExecutionEpoch(request.originExecutionEpoch());
        proposal.setOriginToolCallId(trim(request.originToolCallId(), 128));
        proposal.setSource(request.source() == null || request.source().isBlank()
                ? SOURCE_AGENT : trim(request.source(), 64));
        proposal.setCreator("student:" + studentId);
        proposal.setStatus(STATUS_PENDING);
        proposal.setExpiresTime(LocalDateTime.now().plusSeconds(EXPIRY_SECONDS));
        try {
            proposalMapper.insert(proposal);
        } catch (Exception raced) {
            AgentProjectConfigProposal racedRow = findByProposalKey(projectId, key);
            if (racedRow != null) {
                return toProposalResult(racedRow);
            }
            cleanupStagingQuietly(projectRoot, stageId);
            throw raced;
        }
        if (request.externalChangeId() != null) {
            externalChangeService.materialize(studentId, projectId, request.externalChangeId(),
                    proposal.getProposalId());
        }
        recoveryService.appendAudit(studentId, projectId, EVENT_PROPOSED, proposal.getCreator(),
                proposal.getReason(), null, STATUS_PENDING, null, proposal.getCandidateConfigDigest(),
                proposal.getChangedPathSummary(), proposal.getOriginTaskId(),
                proposal.getOriginExecutionEpoch(),
                AgentProjectConfigApplyRecoveryService.auditKey(proposal.getProposalId(), EVENT_PROPOSED));
        return toProposalResult(proposal);
    }

    /** Lists redacted proposal summaries for the owner's project. */
    public List<ProposalSummary> listProposals(Integer studentId, Integer projectId) {
        owned(studentId, projectId);
        return proposalMapper.selectList(new QueryWrapper<AgentProjectConfigProposal>()
                        .eq("project_id", projectId)
                        .orderByDesc("proposal_id"))
                .stream().map(this::toSummary).toList();
    }

    /** Returns the redacted detail plus audit references for the owner's proposal. */
    public ProposalDetail getProposal(Integer studentId, Integer projectId, Long proposalId) {
        owned(studentId, projectId);
        AgentProjectConfigProposal proposal = ownedProposal(projectId, proposalId);
        List<Long> auditEventIds = recoveryService.auditEventIds(studentId, projectId,
                AgentProjectConfigApplyRecoveryService.auditKey(proposalId, ""));
        return new ProposalDetail(proposal.getProposalId(), proposal.getStatus(),
                proposal.getBaseRevision(), proposal.getCandidateConfigDigest(),
                proposal.getPatchReference(), proposal.getChangedPathSummary(), proposal.getReason(),
                proposal.getSource(), proposal.getCreator(), proposal.getOriginTaskId(),
                proposal.getOriginExecutionEpoch(), proposal.getOriginToolCallId(),
                proposal.getExpiresTime(), proposal.getCreateTime(), proposal.getDecisionTime(),
                proposal.getAppliedRevision(), auditEventIds);
    }

    /**
     * Decides one proposal as the project owner. Repeated identical decision keys return the
     * first durable result; expiry and stale bases CAS to typed terminal states with real
     * HTTP 410/409; approve runs the recoverable apply.
     */
    public DecisionResult decide(Integer studentId, Integer projectId, Long proposalId,
                                 DecisionRequest request) {
        owned(studentId, projectId);
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            throw new ProposalException(400, "INVALID_DECISION", "decision is required");
        }
        String decision = request.decision().trim().toLowerCase(java.util.Locale.ROOT);
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw new ProposalException(400, "INVALID_DECISION",
                    "decision must be 'approve' or 'reject'");
        }
        AgentProjectConfigProposal proposal = ownedProposal(projectId, proposalId);
        String decisionKey = request.decisionIdempotencyKey() == null
                ? null : request.decisionIdempotencyKey().trim();
        if (decisionKey != null && !decisionKey.isEmpty() && decisionKey.length() > DECISION_KEY_MAX_LENGTH) {
            throw new ProposalException(400, "INVALID_KEY",
                    "decisionIdempotencyKey exceeds the maximum length");
        }

        if (decisionKey != null && !decisionKey.isEmpty()) {
            AgentProjectConfigProposal decided = findByDecisionKey(projectId, decisionKey);
            if (decided != null) {
                if (STATUS_APPLYING.equals(decided.getStatus())) {
                    recoveryService.reconcile(studentId, projectId, decided.getProposalId());
                    decided = proposalMapper.selectById(decided.getProposalId());
                }
                return decided(studentId, projectId, decided);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        if (proposal.getExpiresTime() != null && now.isAfter(proposal.getExpiresTime())) {
            int updated = casStatus(proposalId, STATUS_PENDING, STATUS_EXPIRED);
            if (updated > 0) {
                recoveryService.appendAudit(studentId, projectId, EVENT_EXPIRED, "student:" + studentId,
                        null, STATUS_PENDING, STATUS_EXPIRED, null, proposal.getCandidateConfigDigest(),
                        proposal.getChangedPathSummary(), proposal.getOriginTaskId(),
                        proposal.getOriginExecutionEpoch(),
                        AgentProjectConfigApplyRecoveryService.auditKey(proposalId, EVENT_EXPIRED));
                completeDecisionFlow(studentId, projectId, proposalMapper.selectById(proposalId),
                        "PROPOSAL_EXPIRED");
                throw new ProposalException(410, "PROPOSAL_EXPIRED",
                        "Proposal expired; create a new proposal");
            }
            proposal = proposalMapper.selectById(proposalId);
        }
        AgentProjectConfigRevision head = currentRevision(projectId);
        if (request.expectedRevision() != null && head != null
                && !request.expectedRevision().equals(head.getRevision())) {
            throw new ProposalException(409, "STALE_REVISION",
                    "Stale revision; reload the project configuration");
        }
        if (head == null || !proposal.getBaseRevision().equals(head.getRevision())) {
            int updated = casStatus(proposalId, STATUS_PENDING, STATUS_STALE);
            if (updated > 0) {
                recoveryService.appendAudit(studentId, projectId, EVENT_CONFLICTED,
                        "student:" + studentId, null, STATUS_PENDING, STATUS_STALE,
                        head == null ? null : head.getConfigDigest(),
                        proposal.getCandidateConfigDigest(), proposal.getChangedPathSummary(),
                        proposal.getOriginTaskId(), proposal.getOriginExecutionEpoch(),
                        AgentProjectConfigApplyRecoveryService.auditKey(proposalId, EVENT_CONFLICTED));
                completeDecisionFlow(studentId, projectId, proposalMapper.selectById(proposalId),
                        "STALE_REVISION");
                throw new ProposalException(409, "STALE_REVISION",
                        "Proposal is based on a stale revision");
            }
            return decided(studentId, projectId, proposalMapper.selectById(proposalId));
        }
        if (!STATUS_PENDING.equals(proposal.getStatus())) {
            return decided(studentId, projectId, proposal);
        }
        String actor = "student:" + studentId;
        if ("reject".equals(decision)) {
            int updated = casDecision(proposalId, STATUS_PENDING, STATUS_REJECTED,
                    decisionKey, actor, now);
            if (updated == 0) {
                return racedDecision(studentId, projectId, proposalId, decisionKey);
            }
            recoveryService.appendAudit(studentId, projectId, EVENT_REJECTED, actor,
                    null, STATUS_PENDING, STATUS_REJECTED, head.getConfigDigest(),
                    proposal.getCandidateConfigDigest(), proposal.getChangedPathSummary(),
                    proposal.getOriginTaskId(), proposal.getOriginExecutionEpoch(),
                    AgentProjectConfigApplyRecoveryService.auditKey(proposalId, EVENT_REJECTED));
            return decided(studentId, projectId, proposalMapper.selectById(proposalId));
        }

        int updated = casDecision(proposalId, STATUS_PENDING, STATUS_APPLYING,
                decisionKey, actor, now);
        if (updated == 0) {
            return racedDecision(studentId, projectId, proposalId, decisionKey);
        }
        recoveryService.appendAudit(studentId, projectId, EVENT_APPROVED, actor, null,
                STATUS_PENDING, STATUS_APPLYING, head.getConfigDigest(),
                proposal.getCandidateConfigDigest(), proposal.getChangedPathSummary(),
                proposal.getOriginTaskId(), proposal.getOriginExecutionEpoch(),
                AgentProjectConfigApplyRecoveryService.auditKey(proposalId, EVENT_APPROVED));
        ApplyResult result = recoveryService.reconcile(studentId, projectId, proposalId);
        if (STATUS_FAILED.equals(result.status())) {
            completeDecisionFlow(studentId, projectId, proposalMapper.selectById(proposalId),
                    result.failureReason());
            if (AgentProjectConfigApplyRecoveryService.FAILURE_CONFLICT.equals(result.failureReason())) {
                throw new ProposalException(409, "STALE_REVISION",
                        "Proposal apply conflicted with a newer revision", result.errors());
            }
            throw new ProposalException(400, "APPLY_FAILED",
                    "Proposal apply failed: " + result.failureReason(), result.errors());
        }
        return decided(studentId, projectId, proposalMapper.selectById(proposalId));
    }

    /**
     * Materializes a pending external-change observation into an {@code external_change}
     * proposal through the same ownership and CAS authority. The candidate is the complete
     * currently observed protected tree (including undeclared files), so the apply never
     * discards an externally visible file.
     */
    public ProposalResult materializeExternalChange(Integer studentId, Integer projectId,
                                                    Long externalChangeId, String idempotencyKey,
                                                    String reason) {
        StudentProject project = owned(studentId, projectId);
        AgentProjectConfigExternalChangeService.PendingExternalChange pending =
                externalChangeService.requirePendingById(studentId, projectId, externalChangeId);
        String key = normalizedKey(idempotencyKey, 192, "idempotencyKey");
        AgentProjectConfigProposal existing = findByProposalKey(projectId, key);
        if (existing != null) {
            return toProposalResult(existing);
        }
        AgentProjectConfigRevision head = currentRevision(projectId);
        if (head == null || !head.getRevision().equals(pending.baseRevision())) {
            throw new ProposalException(409, "STALE_REVISION",
                    "The observed change is no longer based on the accepted revision");
        }
        Path projectRoot = Path.of(project.getWorkspacePath());
        Map<String, String> candidate;
        try {
            new AgentProjectConfigReader().read(projectRoot);
            candidate = TreeDigests.readRawTree(projectRoot);
        } catch (AgentProjectConfigReader.ConfigReadException e) {
            throw new ProposalException(400, "INVALID_CANDIDATE",
                    "the externally edited tree is invalid", e.errors());
        }
        if (candidate.isEmpty()) {
            throw new ProposalException(400, "INVALID_CANDIDATE",
                    "the externally edited tree is empty");
        }
        TreeDigests.ScanResult scan = TreeDigests.scanCandidate(candidate);
        if (!scan.valid()) {
            throw new ProposalException(400, "INVALID_CANDIDATE",
                    "the externally edited tree is invalid", scan.errors());
        }
        List<String> changedPaths = TreeDigests.changedPaths(TreeDigests.headFiles(head),
                scan.fileHashes());
        String stageId = AgentProjectConfigApplyRecoveryService.stageId(projectId, key);
        try {
            for (Map.Entry<String, String> entry : candidate.entrySet()) {
                fileWriter.stage(projectRoot, stageId, entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            throw new ProposalException(500, "STAGING_FAILED", "unable to stage the candidate");
        } catch (PathViolationException e) {
            throw new ProposalException(400, "INVALID_CANDIDATE", "invalid candidate path");
        }

        AgentProjectConfigProposal proposal = new AgentProjectConfigProposal();
        proposal.setStudentId(studentId);
        proposal.setProjectId(projectId);
        proposal.setProposalKey(key);
        proposal.setBaseRevision(pending.baseRevision());
        proposal.setCandidateConfigDigest(scan.configDigest());
        proposal.setPatchReference(AgentProjectConfigRevisionService.TREE_REFERENCE_PREFIX + scan.treeDigest());
        proposal.setChangedPathSummary(AgentProjectConfigExternalChangeService.summarize(changedPaths));
        proposal.setReason(trim(reason, 2048));
        proposal.setSource(SOURCE_EXTERNAL_CHANGE);
        proposal.setCreator("student:" + studentId);
        proposal.setStatus(STATUS_PENDING);
        proposal.setExpiresTime(LocalDateTime.now().plusSeconds(EXPIRY_SECONDS));
        try {
            proposalMapper.insert(proposal);
        } catch (Exception raced) {
            AgentProjectConfigProposal racedRow = findByProposalKey(projectId, key);
            if (racedRow != null) {
                return toProposalResult(racedRow);
            }
            cleanupStagingQuietly(projectRoot, stageId);
            throw raced;
        }
        externalChangeService.materialize(studentId, projectId, externalChangeId,
                proposal.getProposalId());
        recoveryService.appendAudit(studentId, projectId, EVENT_PROPOSED, proposal.getCreator(),
                proposal.getReason(), null, STATUS_PENDING, null, proposal.getCandidateConfigDigest(),
                proposal.getChangedPathSummary(), null, null,
                AgentProjectConfigApplyRecoveryService.auditKey(proposal.getProposalId(), EVENT_PROPOSED));
        recoveryService.appendAudit(studentId, projectId, EVENT_EXTERNAL_CHANGE_DETECTED,
                proposal.getCreator(), "external change materialized", STATUS_PENDING,
                STATUS_PENDING, null, proposal.getCandidateConfigDigest(),
                proposal.getChangedPathSummary(), null, null,
                "external-change-" + externalChangeId + "-detected");
        return toProposalResult(proposal);
    }

    private DecisionResult racedDecision(Integer studentId, Integer projectId, Long proposalId,
                                         String decisionKey) {
        if (decisionKey != null && !decisionKey.isEmpty()) {
            AgentProjectConfigProposal raced = findByDecisionKey(projectId, decisionKey);
            if (raced != null) {
                if (STATUS_APPLYING.equals(raced.getStatus())) {
                    recoveryService.reconcile(raced.getStudentId(), projectId, raced.getProposalId());
                    raced = proposalMapper.selectById(raced.getProposalId());
                }
                return decided(studentId, projectId, raced);
            }
        }
        return decided(studentId, projectId, proposalMapper.selectById(proposalId));
    }

    private int casStatus(Long proposalId, String from, String to) {
        return proposalMapper.update(null, new UpdateWrapper<AgentProjectConfigProposal>()
                .eq("proposal_id", proposalId)
                .eq("status", from)
                .set("status", to));
    }

    private int casDecision(Long proposalId, String from, String to, String decisionKey,
                            String actor, LocalDateTime decisionTime) {
        try {
            return proposalMapper.update(null, new UpdateWrapper<AgentProjectConfigProposal>()
                    .eq("proposal_id", proposalId)
                    .eq("status", from)
                    .set("status", to)
                    .set("decision_idempotency_key", decisionKey)
                    .set("decision_actor", actor)
                    .set("decision_time", decisionTime));
        } catch (org.springframework.dao.DuplicateKeyException duplicateKeyRaced) {
            return 0;
        } catch (org.apache.ibatis.exceptions.PersistenceException persistenceRaced) {
            if (isDuplicateKey(persistenceRaced)) {
                return 0;
            }
            throw persistenceRaced;
        }
    }

    /**
     * Whether the failure is a unique-key race on the decision idempotency key. Matches the
     * SQL state of the wrapped {@link java.sql.SQLException} ({@code 23505} on H2/PostgreSQL,
     * {@code 23000/1062} on MySQL) so that unexpected database failures are never swallowed.
     */
    private static boolean isDuplicateKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                if ("23505".equals(sql.getSQLState())) {
                    return true;
                }
                if ("23000".equals(sql.getSQLState()) && sql.getErrorCode() == 1062) {
                    return true;
                }
            }
        }
        return false;
    }

    private AgentProjectConfigProposal ownedProposal(Integer projectId, Long proposalId) {
        AgentProjectConfigProposal proposal = proposalMapper.selectOne(
                new QueryWrapper<AgentProjectConfigProposal>()
                        .eq("project_id", projectId)
                        .eq("proposal_id", proposalId)
                        .last("LIMIT 1"));
        if (proposal == null) {
            throw new ProposalException(404, "NOT_FOUND",
                    AgentProjectConfigOwnership.NOT_FOUND_MESSAGE);
        }
        return proposal;
    }

    private AgentProjectConfigProposal findByProposalKey(Integer projectId, String key) {
        return proposalMapper.selectOne(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", projectId)
                .eq("proposal_key", key)
                .last("LIMIT 1"));
    }

    private AgentProjectConfigProposal findByDecisionKey(Integer projectId, String key) {
        return proposalMapper.selectOne(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", projectId)
                .eq("decision_idempotency_key", key)
                .last("LIMIT 1"));
    }

    private AgentProjectConfigRevision currentRevision(Integer projectId) {
        return revisionMapper.selectOne(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", projectId)
                .orderByDesc("revision")
                .last("LIMIT 1"));
    }

    /** 决策后恢复任务的 claim 结果投影；未 claim 时为 null。 */
    public record DecisionFlowOutcome(String interactionId, Long resumeEpoch) {
        static DecisionFlowOutcome of(AgentRunInteraction interaction, InteractionClaimOutcome claim) {
            return claim == null || !claim.claimed() ? null
                    : new DecisionFlowOutcome(interaction.getInteractionId(), claim.lease().epoch());
        }
    }

    private DecisionResult toDecisionResult(AgentProjectConfigProposal proposal) {
        return toDecisionResult(proposal, null);
    }

    private DecisionResult toDecisionResult(AgentProjectConfigProposal proposal,
                                            DecisionFlowOutcome flow) {
        if (proposal == null) {
            throw new ProposalException(404, "NOT_FOUND", AgentProjectConfigOwnership.NOT_FOUND_MESSAGE);
        }
        return new DecisionResult(proposal.getProposalId(), proposal.getStatus(),
                proposal.getAppliedRevision(), proposal.getDecisionTime(),
                flow == null ? null : flow.interactionId(),
                flow == null ? null : flow.resumeEpoch());
    }

    /**
     * Task 2.3 决策路径的单一收口：proposal CAS 已 durable 后，这里按终态投影任务事件、
     * 解决等待中的 config_proposal interaction、经 Task 0.3 事务性 claim 领取新 epoch
     * dispatch，并把租约直接交接给执行循环。proposal 表始终是权威事实；本方法对
     * 重复决策 / 崩溃重放幂等（respond replay、claim 幂等键、事件确定性键）。
     * reasonCodeHint 只用于 FAILED 事件与响应 payload；重放时未知则回退 APPLY_FAILED。
     */
    private DecisionResult decided(Integer studentId, Integer projectId, AgentProjectConfigProposal proposal) {
        DecisionFlowOutcome flow = proposal == null ? null
                : completeDecisionFlow(studentId, projectId, proposal, null);
        return toDecisionResult(proposal, flow);
    }

    private DecisionFlowOutcome completeDecisionFlow(Integer studentId, Integer projectId,
                                                     AgentProjectConfigProposal proposal,
                                                     String reasonCodeHint) {
        if (proposal == null) {
            return null;
        }
        String status = proposal.getStatus();
        if (STATUS_APPLIED.equals(status)) {
            emitDecidedEvent(proposal, "approved");
            emitAppliedEvent(proposal);
            return resumeTaskAfterDecision(studentId, projectId, proposal,
                    AgentRunInteraction.STATUS_APPROVED, null);
        }
        if (STATUS_REJECTED.equals(status)) {
            emitDecidedEvent(proposal, "rejected");
            return resumeTaskAfterDecision(studentId, projectId, proposal,
                    AgentRunInteraction.STATUS_REJECTED, null);
        }
        if (STATUS_EXPIRED.equals(status) || STATUS_STALE.equals(status) || STATUS_FAILED.equals(status)) {
            String reasonCode = reasonCodeHint == null || reasonCodeHint.isBlank()
                    ? "APPLY_FAILED" : reasonCodeHint;
            emitFailedEvent(proposal, reasonCode);
            return resumeTaskAfterDecision(studentId, projectId, proposal,
                    AgentRunInteraction.STATUS_REJECTED, reasonCode);
        }
        return null;
    }

    /**
     * 在决策路径内解决 interaction 并恢复任务：只允许在 proposal 终态与 interaction
     * 状态一致时 claim；不一致（例如未经决策被 resolve 的交互）绝不恢复任务。
     * DEFERRED 有界重试；REJECTED 或重试耗尽时保持已解决状态，由同幂等键重放 heal。
     */
    private DecisionFlowOutcome resumeTaskAfterDecision(Integer studentId, Integer projectId,
                                                        AgentProjectConfigProposal proposal,
                                                        String desiredStatus, String reasonCode) {
        if (proposal.getOriginTaskId() == null || interactionService == null || lifecycleService == null
                || taskService == null || loopEngine == null) {
            return null;
        }
        Long taskId = proposal.getOriginTaskId();
        AgentRunInteraction interaction = interactionService.findConfigProposalInteraction(
                projectId, taskId, proposal.getProposalId());
        if (interaction == null) {
            return null;
        }
        String currentStatus = interaction.getStatus();
        if ("waiting".equals(currentStatus)) {
            try {
                interaction = interactionService.respond(studentId, projectId,
                        interaction.getInteractionId(), desiredStatus,
                        decisionResponsePayload(proposal, desiredStatus, reasonCode));
            } catch (IllegalArgumentException mismatched) {
                log.warn("CONFIG_PROPOSAL_INTERACTION_UNRESOLVABLE taskId={} proposalId={} interactionId={}",
                        taskId, proposal.getProposalId(), interaction.getInteractionId());
                return null;
            }
            currentStatus = interaction.getStatus();
        }
        boolean consistent = desiredStatus.equals(currentStatus)
                || (AgentRunInteraction.STATUS_REJECTED.equals(desiredStatus) && "timed_out".equals(currentStatus));
        if (!consistent) {
            log.warn("CONFIG_PROPOSAL_INTERACTION_INCONSISTENT taskId={} proposalId={} interactionStatus={} desired={}",
                    taskId, proposal.getProposalId(), currentStatus, desiredStatus);
            return null;
        }
        InteractionClaimOutcome claim = claimWithRetry(studentId, projectId, taskId,
                interaction.getInteractionId());
        if (claim == null || !claim.claimed()) {
            log.warn("CONFIG_PROPOSAL_CLAIM_NOT_RESUMED taskId={} proposalId={} outcome={}",
                    taskId, proposal.getProposalId(), claim == null ? "null" : claim.outcome());
            return null;
        }
        enqueueResume(studentId, projectId, taskId, interaction, proposal,
                decisionResponsePayload(proposal, desiredStatus, reasonCode), claim);
        return DecisionFlowOutcome.of(interaction, claim);
    }

    private InteractionClaimOutcome claimWithRetry(Integer studentId, Integer projectId, Long taskId,
                                                   String interactionId) {
        InteractionClaimOutcome outcome = null;
        for (int attempt = 0; attempt < MAX_CLAIM_RETRIES; attempt++) {
            outcome = lifecycleService.claimConfigProposalDispatch(taskId, studentId, projectId, interactionId);
            if (outcome.claimed() || outcome.outcome() == InteractionClaimOutcome.Outcome.REJECTED) {
                return outcome;
            }
            try {
                Thread.sleep(CLAIM_RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return outcome;
            }
        }
        return outcome == null ? InteractionClaimOutcome.deferred() : outcome;
    }

    /** 与调度器一致：把事务性 claim 拿到的租约直接交接给执行循环，不重复竞争。 */
    private void enqueueResume(Integer studentId, Integer projectId, Long taskId,
                               AgentRunInteraction interaction, AgentProjectConfigProposal proposal,
                               Map<String, Object> responsePayload, InteractionClaimOutcome claim) {
        AgentTask task = taskService.getOwnedTask(studentId, projectId, taskId);
        if (task == null) {
            log.warn("CONFIG_PROPOSAL_RESUME_TASK_MISSING taskId={} proposalId={}",
                    taskId, proposal.getProposalId());
            return;
        }
        String continuation = "A pending config proposal interaction has been resolved.\n"
                + "Interaction type: " + interaction.getInteractionType() + "\n"
                + "Resolution status: " + interaction.getStatus() + "\n"
                + "Proposal ID: " + proposal.getProposalId() + "\n"
                + "Proposal status: " + proposal.getStatus() + "\n"
                + "Original interaction payload: " + redact(interaction.getRequestPayload()) + "\n"
                + "User response payload: " + redact(java.util.Map.copyOf(responsePayload)) + "\n"
                + "Do not automatically repeat the propose_project_config tool call. Reassess the current workspace and choose the next safe action.";
        AgentStreamRequest request = AgentRunContinuationRequestFactory.fromTask(task, continuation);
        loopEngine.resume(studentId, projectId, request, taskId, true, claim.lease(), claim.interaction());
    }

    /** 脱敏后的响应 payload：只含 ID/decision/终态/reason，绝不含候选内容或秘密。 */
    private Map<String, Object> decisionResponsePayload(AgentProjectConfigProposal proposal,
                                                        String decision, String reasonCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", proposal.getProposalId());
        payload.put("decision", decision);
        payload.put("proposalStatus", proposal.getStatus());
        payload.put("decisionTime", proposal.getDecisionTime() == null
                ? LocalDateTime.now().toString() : proposal.getDecisionTime().toString());
        if (proposal.getAppliedRevision() != null) {
            payload.put("appliedRevision", proposal.getAppliedRevision());
        }
        if (reasonCode != null && !reasonCode.isBlank()) {
            payload.put("reasonCode", reasonCode);
        }
        return payload;
    }

    private void emitDecidedEvent(AgentProjectConfigProposal proposal, String decision) {
        if (proposal.getOriginTaskId() == null || lifecycleService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", proposal.getProposalId());
        payload.put("decision", decision);
        payload.put("actor", proposal.getDecisionActor() == null
                ? "student:" + proposal.getStudentId() : proposal.getDecisionActor());
        payload.put("decisionTime", proposal.getDecisionTime() == null
                ? LocalDateTime.now().toString() : proposal.getDecisionTime().toString());
        appendEventGuarded(proposal, "CONFIG_PROPOSAL_DECIDED", payload,
                "config-proposal-decision:v1:" + proposal.getProposalId() + ":" + proposal.getStatus());
    }

    private void emitAppliedEvent(AgentProjectConfigProposal proposal) {
        if (proposal.getOriginTaskId() == null || lifecycleService == null) {
            return;
        }
        AgentProjectConfigRevision base = revisionMapper.selectOne(new QueryWrapper<AgentProjectConfigRevision>()
                .eq("project_id", proposal.getProjectId())
                .eq("revision", proposal.getBaseRevision())
                .last("LIMIT 1"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", proposal.getProposalId());
        payload.put("revision", proposal.getAppliedRevision());
        payload.put("beforeDigest", base == null ? null : base.getConfigDigest());
        payload.put("afterDigest", proposal.getCandidateConfigDigest());
        payload.put("evidenceReference", proposal.getPatchReference());
        appendEventGuarded(proposal, "CONFIG_REVISION_APPLIED", payload,
                "config-proposal-applied:v1:" + proposal.getProposalId());
    }

    private void emitFailedEvent(AgentProjectConfigProposal proposal, String reasonCode) {
        if (proposal.getOriginTaskId() == null || lifecycleService == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", proposal.getProposalId());
        payload.put("reasonCode", reasonCode);
        payload.put("proposalStatus", proposal.getStatus());
        appendEventGuarded(proposal, "CONFIG_PROPOSAL_FAILED", payload,
                "config-proposal-failed:v1:" + proposal.getProposalId() + ":" + proposal.getStatus());
    }

    /** 事件是任务投影；追加失败不能回滚已 durable 的 proposal 决策，由同键重放补齐。 */
    private void appendEventGuarded(AgentProjectConfigProposal proposal, String eventType,
                                    Map<String, Object> payload, String idempotencyKey) {
        try {
            lifecycleService.appendEvent(proposal.getOriginTaskId(), eventType, payload, idempotencyKey);
        } catch (RuntimeException projectionFailure) {
            log.warn("CONFIG_PROPOSAL_EVENT_FAILED taskId={} proposalId={} eventType={}",
                    proposal.getOriginTaskId(), proposal.getProposalId(), eventType, projectionFailure);
        }
    }

    private String redact(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 4_000 ? text : text.substring(0, 4_000) + "...";
    }

    private ProposalResult toProposalResult(AgentProjectConfigProposal proposal) {
        return new ProposalResult(proposal.getProposalId(), proposal.getStatus(),
                proposal.getBaseRevision(), proposal.getCandidateConfigDigest(),
                proposal.getChangedPathSummary(), proposal.getExpiresTime());
    }

    private ProposalSummary toSummary(AgentProjectConfigProposal proposal) {
        return new ProposalSummary(proposal.getProposalId(), proposal.getStatus(),
                proposal.getBaseRevision(), proposal.getCandidateConfigDigest(),
                proposal.getChangedPathSummary(), proposal.getReason(), proposal.getSource(),
                proposal.getCreator(), proposal.getExpiresTime(), proposal.getDecisionTime(),
                proposal.getAppliedRevision());
    }

    private StudentProject owned(Integer studentId, Integer projectId) {
        try {
            return AgentProjectConfigOwnership.requireOwned(studentId, projectId,
                    studentProjectService);
        } catch (ProjectConfigNotFoundException e) {
            throw new ProposalException(404, "NOT_FOUND",
                    AgentProjectConfigOwnership.NOT_FOUND_MESSAGE);
        }
    }

    private String normalizedKey(String key, int maxLength, String field) {
        String normalized = key == null ? null : key.trim();
        if (normalized == null || normalized.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        if (normalized.length() > maxLength) {
            throw new ProposalException(400, "INVALID_KEY",
                    field + " exceeds the maximum length");
        }
        return normalized;
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private void cleanupStagingQuietly(Path projectRoot, String stageId) {
        try {
            fileWriter.cleanupStaging(projectRoot, stageId);
        } catch (IOException ignored) {
            // Inert staging leftovers are overwritten by the next identical idempotency key.
        }
    }
}
