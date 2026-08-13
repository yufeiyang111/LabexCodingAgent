package com.labex.labexagent.secret;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectSecretBinding;
import com.labex.mapper.AgentProjectSecretBindingMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issues bounded, scoped secret leases for a single worker run.
 *
 * <p>A lease carries the task, execution epoch, worker run ID, the allowed variable names,
 * the opened binding references and an expiry. Allowed names are environment-variable-shaped
 * names; they are translated through {@link ProjectSecretBindingService#aliasFor} before the
 * binding query, so a production binding created by {@link ProjectSecretBindingService} is
 * always resolved. It only ever exposes the value of a bound, unexpired, configured alias
 * through {@link ScopedSecretLease#value(String)}; unbound names return {@code null} without
 * leaking existence. {@link ScopedSecretLease#close()} wipes the underlying
 * {@link SecretStore} leases exactly once; an expired or closed lease refuses all further
 * access. {@link ScopedSecretLease#value} and {@link ScopedSecretLease#close} are
 * synchronized, so concurrent access never observes a half-erased underlying lease.
 *
 * <p>Allowed names must match a strict environment-variable shape and must not collide with
 * reserved control-plane or host variables ({@code PATH}, {@code HOME}, {@code SHELL},
 * {@code TEMP}, … or anything under the {@code LABEX_} prefix). Command-line injection
 * payloads never reach the worker.
 *
 * <p>This service only issues lease references; actual Worker/terminal/LSP/MCP injection is
 * wired by Task 3.2 and Task 4.2. No second injection path exists here.
 */
@Component
public final class ScopedSecretLeaseService {

    public static final long DEFAULT_LEASE_TTL_SECONDS = 60;
    public static final long MAX_LEASE_TTL_SECONDS = 300;
    public static final int WORKER_RUN_ID_MAX_LENGTH = 128;

    private static final Set<String> RESERVED_NAMES = Set.of(
            "PATH", "HOME", "USER", "USERNAME", "SHELL", "LANG", "LANGUAGE", "LC_ALL",
            "LC_CTYPE", "PWD", "OLDPWD", "TERM", "HOSTNAME", "HOST", "WSLENV", "DISPLAY",
            "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT",
            "TEMP", "TMP", "TMPDIR", "SYSTEMDRIVE", "PROGRAMFILES", "APPDATA",
            "USERPROFILE", "OS", "NUMBER_OF_PROCESSORS", "PSMODULEPATH");
    private static final String RESERVED_PREFIX = "LABEX_";

    private final AgentProjectSecretBindingMapper bindingMapper;
    private final SecretStore secretStore;
    private final Clock clock;

    @Autowired
    public ScopedSecretLeaseService(AgentProjectSecretBindingMapper bindingMapper,
                                    SecretStore secretStore) {
        this(bindingMapper, secretStore, Clock.systemUTC());
    }

    ScopedSecretLeaseService(AgentProjectSecretBindingMapper bindingMapper,
                             SecretStore secretStore, Clock clock) {
        this.bindingMapper = bindingMapper;
        this.secretStore = secretStore;
        this.clock = clock;
    }

    /**
     * Issues one lease for the given worker run, opening the encrypted values of every
     * bound, unexpired, configured alias whose name is in {@code allowedNames}. Denied
     * names fail the whole call, so a worker can never smuggle an unexpected variable in.
     */
    public ScopedSecretLease issue(Integer studentId, Integer projectId, Long taskId, Long epoch,
                                   String workerRunId, Set<String> allowedNames, Duration ttl) {
        if (studentId == null || projectId == null || taskId == null || epoch == null) {
            throw new IllegalArgumentException("studentId, projectId, taskId and epoch are required");
        }
        if (workerRunId == null || workerRunId.isBlank()
                || workerRunId.trim().length() > WORKER_RUN_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("workerRunId is required");
        }
        if (allowedNames == null) {
            throw new IllegalArgumentException("allowedNames is required");
        }
        for (String name : allowedNames) {
            validateName(name);
        }
        Set<String> names = Set.copyOf(allowedNames);
        long ttlSeconds = ttl == null ? DEFAULT_LEASE_TTL_SECONDS : ttl.getSeconds();
        if (ttlSeconds <= 0 || ttlSeconds > MAX_LEASE_TTL_SECONDS) {
            throw new IllegalArgumentException("lease ttl must be between 1 second and "
                    + MAX_LEASE_TTL_SECONDS + " seconds");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, clock.getZone());

        Set<String> aliases = names.stream()
                .map(name -> ProjectSecretBindingService.aliasFor(projectId, name))
                .collect(Collectors.toSet());
        Map<String, AgentProjectSecretBinding> boundByAlias = bindingMapper.selectList(
                        new QueryWrapper<AgentProjectSecretBinding>()
                                .eq("student_id", studentId)
                                .eq("project_id", projectId)
                                .in("alias", aliases))
                .stream()
                .filter(binding -> binding.getConfigured() != null && binding.getConfigured() == 1)
                .filter(binding -> binding.getExpiresAt() != null
                        && binding.getExpiresAt().isAfter(nowLocal))
                .collect(Collectors.toMap(AgentProjectSecretBinding::getAlias, binding -> binding,
                        (first, second) -> first));

        Map<String, SecretStore.SecretLease> opened = new HashMap<>();
        Map<String, Long> bindingIds = new LinkedHashMap<>();
        try {
            for (String name : names) {
                AgentProjectSecretBinding binding = boundByAlias.get(
                        ProjectSecretBindingService.aliasFor(projectId, name));
                if (binding == null) {
                    continue;
                }
                opened.put(name, secretStore.open(ProjectSecretBindingService.BINDING_SCOPE,
                        binding.getEncryptedValue()));
                bindingIds.put(name, binding.getBindingId());
            }
        } catch (RuntimeException openFailure) {
            for (SecretStore.SecretLease lease : opened.values()) {
                try {
                    lease.close();
                } catch (RuntimeException ignored) {
                    // Fail-closed: a partially opened lease is still cleaned up best-effort.
                }
            }
            throw openFailure;
        }
        return new ScopedSecretLease(taskId, epoch, workerRunId.trim(), names,
                Map.copyOf(bindingIds), opened, expiresAt, clock);
    }

    private void validateName(String name) {
        if (name == null || !ProjectSecretBindingService.NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid secret name");
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (RESERVED_NAMES.contains(upper) || upper.startsWith(RESERVED_PREFIX)) {
            throw new IllegalArgumentException("secret name is reserved");
        }
    }

    /**
     * One lease per worker run: task, epoch, worker run ID, allowed names, binding
     * references, expiry and one-time cleanup. Values are exposed only while the lease is
     * open and unexpired. All accessors are synchronized so concurrent {@link #value} and
     * {@link #close} never observe a half-erased underlying lease.
     */
    public static final class ScopedSecretLease implements AutoCloseable {
        private final Long taskId;
        private final Long epoch;
        private final String workerRunId;
        private final Set<String> allowedNames;
        private final Map<String, Long> bindingIds;
        private final Map<String, SecretStore.SecretLease> opened;
        private final Instant expiresAt;
        private final Clock clock;
        private boolean closed;

        private ScopedSecretLease(Long taskId, Long epoch, String workerRunId,
                                  Set<String> allowedNames, Map<String, Long> bindingIds,
                                  Map<String, SecretStore.SecretLease> opened,
                                  Instant expiresAt, Clock clock) {
            this.taskId = taskId;
            this.epoch = epoch;
            this.workerRunId = workerRunId;
            this.allowedNames = allowedNames;
            this.bindingIds = bindingIds;
            this.opened = opened;
            this.expiresAt = expiresAt;
            this.clock = clock;
        }

        public Long taskId() { return taskId; }
        public Long epoch() { return epoch; }
        public String workerRunId() { return workerRunId; }
        public Set<String> allowedNames() { return allowedNames; }
        public Map<String, Long> bindingIds() { return bindingIds; }
        public Instant expiresAt() { return expiresAt; }

        /**
         * Returns the value bound to the allowed name, or {@code null} when the name is
         * allowed but not configured. Refuses closed, expired and out-of-scope names.
         */
        public synchronized String value(String name) {
            if (closed) {
                throw new SecretStore.SecretStoreException("Secret lease is closed");
            }
            if (clock.instant().isAfter(expiresAt)) {
                throw new SecretStore.SecretStoreException("Secret lease has expired");
            }
            if (name == null || !allowedNames.contains(name)) {
                throw new IllegalArgumentException("name is not allowed in this secret lease");
            }
            SecretStore.SecretLease lease = opened.get(name);
            return lease == null ? null : lease.value();
        }

        /** One-time cleanup: closes every underlying SecretStore lease and wipes values. */
        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (SecretStore.SecretLease lease : opened.values()) {
                try {
                    lease.close();
                } catch (RuntimeException ignored) {
                    // Idempotent: an already closed underlying lease is not a failure.
                }
            }
        }

        @Override
        public String toString() {
            return "ScopedSecretLease[taskId=" + taskId + ",epoch=" + epoch
                    + ",workerRunId=" + workerRunId + ",allowedNames=" + allowedNames
                    + ",expiresAt=" + expiresAt + "]";
        }
    }
}
