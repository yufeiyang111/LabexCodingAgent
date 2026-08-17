package com.labex.labexagent.projectconfig;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentProjectConfigExternalChange;
import com.labex.mapper.AgentProjectConfigExternalChangeMapper;
import com.labex.service.StudentProjectService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Persists {@code external_change_pending} observation facts when an external editor changed
 * the protected project configuration tree.
 *
 * <p>This service is deliberately NOT a proposal authority: it only records the observation
 * row with a redacted changed-path summary and never writes the proposal table, never creates
 * a new effective revision and never mutates task state. The owner-only proposal decision
 * flow (Phase 2) materializes these facts into {@code external_change} proposals. One pending
 * record is kept per project / base revision / observed tree digest, so repeated loads of the
 * same external tree are idempotent. Every public entry point (record, find and check)
 * requires the {@link AgentProjectConfigOwnership} gate first, so foreign projects are
 * indistinguishable from not found on every read path.
 */
@Component
public class AgentProjectConfigExternalChangeService {

    public static final String STATUS_PENDING = "external_change_pending";
    public static final String STATUS_APPLIED = "applied";
    public static final int MAX_SUMMARY_PATHS = 20;

    /** A persisted pending-observation fact (never a decision). */
    public record PendingExternalChange(Long id, Long baseRevision, String observedTreeDigest,
                                        String changedPathSummary, LocalDateTime detectedAt) {
    }

    private final StudentProjectService studentProjectService;
    private final AgentProjectConfigExternalChangeMapper externalChangeMapper;

    public AgentProjectConfigExternalChangeService(StudentProjectService studentProjectService,
                                                   AgentProjectConfigExternalChangeMapper externalChangeMapper) {
        this.studentProjectService = studentProjectService;
        this.externalChangeMapper = externalChangeMapper;
    }

    /**
     * Records one pending observation for the authenticated owner's project, or returns the
     * already-persisted record when the same (project, base revision, observed tree digest)
     * observation already exists. Fails closed with the uniform not-found failure when the
     * project does not belong to the caller.
     */
    public PendingExternalChange recordPending(Integer studentId, Integer projectId,
                                               Long baseRevision, String observedTreeDigest,
                                               List<String> changedPaths) {
        AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        PendingExternalChange existing = findPendingObservation(studentId, projectId,
                baseRevision, observedTreeDigest);
        if (existing != null) {
            return existing;
        }
        AgentProjectConfigExternalChange entity = new AgentProjectConfigExternalChange();
        entity.setStudentId(studentId);
        entity.setProjectId(projectId);
        entity.setBaseRevision(baseRevision);
        entity.setObservedTreeDigest(observedTreeDigest);
        entity.setChangedPathSummary(summarize(changedPaths));
        entity.setStatus(STATUS_PENDING);
        entity.setDetectedAt(LocalDateTime.now());
        try {
            externalChangeMapper.insert(entity);
        } catch (Exception duplicate) {
            PendingExternalChange raced = findPendingObservation(studentId, projectId,
                    baseRevision, observedTreeDigest);
            if (raced != null) {
                return raced;
            }
            throw duplicate;
        }
        return toRecord(entity);
    }

    /** Returns the newest pending observation for the owner's project, if any. */
    public Optional<PendingExternalChange> findPending(Integer studentId, Integer projectId) {
        AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        AgentProjectConfigExternalChange entity = externalChangeMapper.selectOne(
                new QueryWrapper<AgentProjectConfigExternalChange>()
                        .eq("student_id", studentId)
                        .eq("project_id", projectId)
                        .eq("status", STATUS_PENDING)
                        .orderByDesc("external_change_id")
                        .last("LIMIT 1"));
        return Optional.ofNullable(entity).map(this::toRecord);
    }

    /** Whether the owner's project currently has a blocking pending observation. */
    public boolean hasPending(Integer studentId, Integer projectId) {
        AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        return externalChangeMapper.selectCount(new QueryWrapper<AgentProjectConfigExternalChange>()
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("status", STATUS_PENDING)) > 0;
    }

    /**
     * Materialization entry used exclusively by {@link AgentProjectConfigProposalService}: links
     * a durable {@code external_change} proposal to the pending observation. The observation
     * stays {@code external_change_pending} until the proposal is applied, so the blocking
     * read path keeps reporting the unresolved tree while the decision is outstanding. A
     * foreign, missing or already-materialized observation fails closed with the uniform
     * not-found failure.
     */
    public PendingExternalChange materialize(Integer studentId, Integer projectId,
                                             Long externalChangeId, Long proposalId) {
        AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        if (proposalId == null) {
            throw new AgentProjectConfigOwnership.ProjectConfigNotFoundException();
        }
        int updated = externalChangeMapper.update(null, new UpdateWrapper<AgentProjectConfigExternalChange>()
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("external_change_id", externalChangeId)
                .eq("status", STATUS_PENDING)
                .set("proposal_id", proposalId));
        if (updated == 0) {
            throw new AgentProjectConfigOwnership.ProjectConfigNotFoundException();
        }
        return toRecord(externalChangeMapper.selectById(externalChangeId));
    }

    /**
     * Marks every pending observation for the apply's (project, base revision, observed tree
     * digest) as {@code applied} once the materialized proposal reaches {@code applied}. This
     * also resolves observation rows recorded by a concurrent read in the publish window.
     */
    public void markApplied(Integer studentId, Integer projectId, Long baseRevision,
                            String observedTreeDigest, Long proposalId) {
        AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        externalChangeMapper.update(null, new UpdateWrapper<AgentProjectConfigExternalChange>()
                .eq("student_id", studentId)
                .eq("project_id", projectId)
                .eq("base_revision", baseRevision)
                .eq("observed_tree_digest", observedTreeDigest)
                .eq("status", STATUS_PENDING)
                .set("status", STATUS_APPLIED));
    }

    /** Returns the pending observation by id, or fails closed for foreign/missing/stale ids. */
    public PendingExternalChange requirePendingById(Integer studentId, Integer projectId,
                                                    Long externalChangeId) {
        AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        if (externalChangeId == null) {
            throw new AgentProjectConfigOwnership.ProjectConfigNotFoundException();
        }
        AgentProjectConfigExternalChange entity = externalChangeMapper.selectOne(
                new QueryWrapper<AgentProjectConfigExternalChange>()
                        .eq("student_id", studentId)
                        .eq("project_id", projectId)
                        .eq("external_change_id", externalChangeId));
        if (entity == null || !STATUS_PENDING.equals(entity.getStatus())) {
            throw new AgentProjectConfigOwnership.ProjectConfigNotFoundException();
        }
        return toRecord(entity);
    }

    private PendingExternalChange findPendingObservation(Integer studentId, Integer projectId,
                                                         Long baseRevision, String observedTreeDigest) {
        AgentProjectConfigExternalChange entity = externalChangeMapper.selectOne(
                new QueryWrapper<AgentProjectConfigExternalChange>()
                        .eq("student_id", studentId)
                        .eq("project_id", projectId)
                        .eq("base_revision", baseRevision)
                        .eq("observed_tree_digest", observedTreeDigest)
                        .eq("status", STATUS_PENDING)
                        .last("LIMIT 1"));
        return entity == null ? null : toRecord(entity);
    }

    private PendingExternalChange toRecord(AgentProjectConfigExternalChange entity) {
        return new PendingExternalChange(entity.getExternalChangeId(), entity.getBaseRevision(),
                entity.getObservedTreeDigest(), entity.getChangedPathSummary(), entity.getDetectedAt());
    }

    /** Redacts the summary to a bounded, sorted path list with no file content. */
    public static String summarize(List<String> changedPaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            return null;
        }
        List<String> sorted = changedPaths.stream().sorted().toList();
        if (sorted.size() <= MAX_SUMMARY_PATHS) {
            return String.join(", ", sorted);
        }
        return String.join(", ", sorted.subList(0, MAX_SUMMARY_PATHS))
                + " (+" + (sorted.size() - MAX_SUMMARY_PATHS) + " more)";
    }
}
