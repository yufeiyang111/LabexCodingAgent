package com.labex.labexagent.secret;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectSecretBinding;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership;
import com.labex.labexagent.projectconfig.AgentProjectConfigOwnership.ProjectConfigNotFoundException;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalDetail;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalException;
import com.labex.mapper.AgentProjectSecretBindingMapper;
import com.labex.service.StudentProjectService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Write-only project secret input authority for the proposal approval flow.
 *
 * <p>The endpoint accepts one field ID and one value, validates project ownership through
 * {@link AgentProjectConfigOwnership} and proposal ownership plus pending status through the
 * proposal service, encrypts the value immediately with the existing {@link SecretStore} and
 * persists only the encrypted envelope plus binding metadata. The plaintext is never
 * serialized into any interaction response, transcript, event, log or error message; the API
 * returns only the deterministic alias and the configured flag.
 *
 * <p>The field ID must be a strict environment-variable-shaped name (shared
 * {@link #NAME_PATTERN} with the lease side), so the derived alias
 * ({@code "p" + projectId + "_" + fieldId}) is always a name the worker lease can resolve
 * losslessly. Dotted or otherwise non-name field IDs are rejected at binding time.
 *
 * <p>One-time idempotency is enforced by the {@code (project_id, idempotency_key)} unique
 * key: a repeated key returns the first durable binding. A field can be bound at most once
 * (unique {@code (project_id, alias)}); rotation requires a new proposal field. Expiry is
 * computed from an injected clock (UTC by default), the same convention the lease side uses.
 */
@Component
public class ProjectSecretBindingService {

    /** Encrypted envelopes live under the existing SecretStore under the WORKER_ENV scope. */
    public static final SecretStore.SecretScope BINDING_SCOPE = SecretStore.SecretScope.WORKER_ENV;

    /** Shared name contract: valid environment-variable shapes, usable as lease names. */
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static final int FIELD_ID_MAX_LENGTH = 128;
    public static final int ALIAS_MAX_LENGTH = 128;
    public static final int VALUE_MAX_LENGTH = 1000;
    public static final int KEY_MAX_LENGTH = 192;
    public static final int DEFAULT_TTL_SECONDS = 3600;
    public static final int MIN_TTL_SECONDS = 60;
    public static final int MAX_TTL_SECONDS = 86400;

    /** Write-only input; the value must never be persisted, logged or serialized. */
    public record SecretInputRequest(String fieldId, String value, String idempotencyKey,
                                     Integer ttlSeconds) {
    }

    /** Redacted result: only the durable alias and the configured flag. */
    public record SecretInputResult(Long bindingId, String alias, boolean configured) {
    }

    /**
     * Deterministic binding alias for a field. The lease side translates allowed names back
     * through this same function before querying, so production bindings are always found.
     */
    public static String aliasFor(Integer projectId, String fieldId) {
        return "p" + projectId + "_" + fieldId;
    }

    private final StudentProjectService studentProjectService;
    private final AgentProjectConfigProposalService proposalService;
    private final AgentProjectSecretBindingMapper bindingMapper;
    private final SecretStore secretStore;
    private final Clock clock;

    @Autowired
    public ProjectSecretBindingService(StudentProjectService studentProjectService,
                                       AgentProjectConfigProposalService proposalService,
                                       AgentProjectSecretBindingMapper bindingMapper,
                                       SecretStore secretStore) {
        this(studentProjectService, proposalService, bindingMapper, secretStore, Clock.systemUTC());
    }

    ProjectSecretBindingService(StudentProjectService studentProjectService,
                                AgentProjectConfigProposalService proposalService,
                                AgentProjectSecretBindingMapper bindingMapper,
                                SecretStore secretStore, Clock clock) {
        this.studentProjectService = studentProjectService;
        this.proposalService = proposalService;
        this.bindingMapper = bindingMapper;
        this.secretStore = secretStore;
        this.clock = clock;
    }

    /**
     * Binds one secret value to one pending proposal field. Ownership of the project and the
     * proposal is validated first; the value is encrypted before any row is inserted.
     */
    public SecretInputResult inputSecret(Integer studentId, Integer projectId, Long proposalId,
                                         SecretInputRequest request) {
        owned(studentId, projectId);
        if (proposalService != null) {
            requirePendingProposal(studentId, projectId, proposalId);
        }
        if (request == null || request.value() == null || request.value().isBlank()) {
            throw new ProposalException(400, "INVALID_SECRET_INPUT", "value is required");
        }
        String value = request.value();
        if (value.length() > VALUE_MAX_LENGTH) {
            throw new ProposalException(400, "INVALID_SECRET_INPUT",
                    "value exceeds the maximum length");
        }
        String fieldId = request.fieldId() == null ? null : request.fieldId().trim();
        if (fieldId == null || fieldId.isEmpty()) {
            throw new ProposalException(400, "INVALID_FIELD_ID", "fieldId is required");
        }
        if (fieldId.length() > FIELD_ID_MAX_LENGTH || !NAME_PATTERN.matcher(fieldId).matches()) {
            throw new ProposalException(400, "INVALID_FIELD_ID",
                    "fieldId must be an environment-variable-shaped name");
        }
        String alias = aliasFor(projectId, fieldId);
        if (alias.length() > ALIAS_MAX_LENGTH) {
            throw new ProposalException(400, "INVALID_FIELD_ID", "fieldId is too long");
        }
        int ttlSeconds = request.ttlSeconds() == null ? DEFAULT_TTL_SECONDS : request.ttlSeconds();
        if (ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
            throw new ProposalException(400, "INVALID_TTL",
                    "ttlSeconds must be between " + MIN_TTL_SECONDS + " and " + MAX_TTL_SECONDS);
        }
        String key = normalizedKey(request.idempotencyKey());
        AgentProjectSecretBinding existing = findByKey(projectId, key);
        if (existing != null) {
            return toResult(existing);
        }

        SecretStore.StoredSecret stored = secretStore.store(BINDING_SCOPE, value);
        AgentProjectSecretBinding binding = new AgentProjectSecretBinding();
        binding.setStudentId(studentId);
        binding.setProjectId(projectId);
        binding.setProposalId(proposalId);
        binding.setFieldId(fieldId);
        binding.setAlias(alias);
        binding.setEncryptedValue(stored.ciphertext());
        binding.setKeyVersion(stored.keyVersion());
        binding.setTtlSeconds(ttlSeconds);
        binding.setExpiresAt(LocalDateTime.ofInstant(clock.instant(), clock.getZone())
                .plusSeconds(ttlSeconds));
        binding.setIdempotencyKey(key);
        binding.setConfigured(1);
        try {
            bindingMapper.insert(binding);
        } catch (Exception raced) {
            AgentProjectSecretBinding byKey = findByKey(projectId, key);
            if (byKey != null) {
                return toResult(byKey);
            }
            AgentProjectSecretBinding byAlias = findByAlias(projectId, alias);
            if (byAlias != null) {
                throw new ProposalException(409, "ALIAS_ALREADY_BOUND",
                        "Field is already bound; create a new proposal field to rotate it");
            }
            throw raced;
        }
        return toResult(binding);
    }

    /**
     * Proposal authority check for secret input: the proposal must exist (404 when foreign)
     * and must still be pending and unexpired. Expired proposals return HTTP 410, terminal
     * or decided proposals HTTP 409 — identical semantics to the decision CAS.
     */
    private void requirePendingProposal(Integer studentId, Integer projectId, Long proposalId) {
        ProposalDetail detail = proposalService.getProposal(studentId, projectId, proposalId);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        if (detail.expiresTime() != null && now.isAfter(detail.expiresTime())) {
            throw new ProposalException(410, "PROPOSAL_EXPIRED",
                    "Proposal expired; create a new proposal");
        }
        if (!AgentProjectConfigProposalService.STATUS_PENDING.equals(detail.status())) {
            throw new ProposalException(409, "PROPOSAL_NOT_PENDING",
                    "Proposal is not pending; secret input is no longer accepted");
        }
    }

    private AgentProjectSecretBinding findByKey(Integer projectId, String key) {
        return bindingMapper.selectOne(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", projectId)
                .eq("idempotency_key", key)
                .last("LIMIT 1"));
    }

    private AgentProjectSecretBinding findByAlias(Integer projectId, String alias) {
        return bindingMapper.selectOne(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", projectId)
                .eq("alias", alias)
                .last("LIMIT 1"));
    }

    private SecretInputResult toResult(AgentProjectSecretBinding binding) {
        return new SecretInputResult(binding.getBindingId(), binding.getAlias(),
                binding.getConfigured() != null && binding.getConfigured() == 1);
    }

    private void owned(Integer studentId, Integer projectId) {
        try {
            AgentProjectConfigOwnership.requireOwned(studentId, projectId, studentProjectService);
        } catch (ProjectConfigNotFoundException e) {
            throw new ProposalException(404, "NOT_FOUND",
                    AgentProjectConfigOwnership.NOT_FOUND_MESSAGE);
        }
    }

    private String normalizedKey(String key) {
        String normalized = key == null ? null : key.trim();
        if (normalized == null || normalized.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        if (normalized.length() > KEY_MAX_LENGTH) {
            throw new ProposalException(400, "INVALID_KEY",
                    "idempotencyKey exceeds the maximum length");
        }
        return normalized;
    }
}
