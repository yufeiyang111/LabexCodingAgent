package com.labex.labexagent.migration;

import com.labex.entity.AgentLegacyMigrationGate;
import com.labex.mapper.AgentLegacyMigrationGateMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 持久化旧版 reader 的命中遥测和安全删除观察窗口。
 *
 * <p>该服务只保存运维证据，不属于 transcript、任务状态或 Provider 输入。</p>
 */
@Service
public class AgentLegacyMigrationGateService {
    public static final String LEGACY_HISTORY_READER = "legacy_history";
    public static final String LEGACY_CHECKPOINT_READER = "legacy_checkpoint";
    private static final Logger log = LoggerFactory.getLogger(AgentLegacyMigrationGateService.class);

    private final AgentLegacyMigrationGateMapper mapper;
    private final String targetRemovalVersion;
    private final int observationWindowDays;
    private final Clock clock;

    @Autowired
    public AgentLegacyMigrationGateService(
            AgentLegacyMigrationGateMapper mapper,
            @Value("${labex-agent.legacy-migration.target-removal-version:1.1.0}") String targetRemovalVersion,
            @Value("${labex-agent.legacy-migration.observation-window-days:14}") int observationWindowDays) {
        this(mapper, targetRemovalVersion, observationWindowDays, Clock.systemDefaultZone());
    }

    AgentLegacyMigrationGateService(AgentLegacyMigrationGateMapper mapper,
                                    String targetRemovalVersion,
                                    int observationWindowDays,
                                    Clock clock) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.targetRemovalVersion = targetRemovalVersion == null ? "" : targetRemovalVersion.trim();
        this.observationWindowDays = Math.max(1, observationWindowDays);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** 记录一次真实的旧 reader 命中，并重新开始零存量观察窗口。 */
    @Transactional(rollbackFor = Exception.class)
    public GateSnapshot recordReaderHit(String readerKey, long sourceItems) {
        String normalized = requireReaderKey(readerKey);
        LocalDateTime now = now();
        AgentLegacyMigrationGate gate = lockOrCreate(normalized, now);
        gate.setTargetRemovalVersion(targetRemovalVersion);
        gate.setObservationWindowDays(observationWindowDays);
        gate.setReadHitCount(safe(gate.getReadHitCount()) + 1L);
        gate.setSourceItemHitCount(safe(gate.getSourceItemHitCount()) + Math.max(0L, sourceItems));
        gate.setLastReadHitAt(now);
        gate.setZeroInventorySince(null);
        gate.setUpdateTime(now);
        update(gate);
        log.info("LEGACY_READER_HIT readerKey={} hitCount={} sourceItems={} observedAt={}",
                normalized, gate.getReadHitCount(), Math.max(0L, sourceItems), now);
        return snapshot(gate, now);
    }

    /** 刷新 pending inventory；只有归零后才开始连续观察。 */
    @Transactional(rollbackFor = Exception.class)
    public GateSnapshot refreshInventory(String readerKey, long pendingSources) {
        String normalized = requireReaderKey(readerKey);
        long pending = Math.max(0L, pendingSources);
        LocalDateTime now = now();
        AgentLegacyMigrationGate gate = lockOrCreate(normalized, now);
        long previousPending = safe(gate.getPendingSourceCount());
        gate.setTargetRemovalVersion(targetRemovalVersion);
        gate.setObservationWindowDays(observationWindowDays);
        gate.setPendingSourceCount(pending);
        gate.setLastInventoryAt(now);
        if (pending > 0L) {
            gate.setZeroInventorySince(null);
        } else if (previousPending > 0L || gate.getZeroInventorySince() == null) {
            gate.setZeroInventorySince(now);
        }
        gate.setUpdateTime(now);
        update(gate);
        log.info("LEGACY_INVENTORY_REFRESH readerKey={} pendingSources={} zeroInventorySince={} observedAt={}",
                normalized, pending, gate.getZeroInventorySince(), now);
        return snapshot(gate, now);
    }

    public GateSnapshot snapshot(String readerKey) {
        String normalized = requireReaderKey(readerKey);
        AgentLegacyMigrationGate gate = mapper.selectById(normalized);
        if (gate == null) {
            return emptySnapshot(normalized, now());
        }
        return snapshot(gate, now());
    }

    private AgentLegacyMigrationGate lockOrCreate(String readerKey, LocalDateTime now) {
        AgentLegacyMigrationGate gate = mapper.selectForUpdate(readerKey);
        if (gate != null) {
            return gate;
        }
        AgentLegacyMigrationGate created = new AgentLegacyMigrationGate();
        created.setReaderKey(readerKey);
        created.setTargetRemovalVersion(targetRemovalVersion);
        created.setObservationWindowDays(observationWindowDays);
        created.setReadHitCount(0L);
        created.setSourceItemHitCount(0L);
        created.setPendingSourceCount(0L);
        created.setCreateTime(now);
        created.setUpdateTime(now);
        try {
            if (mapper.insert(created) != 1) {
                throw new IllegalStateException("Unable to initialize legacy migration gate " + readerKey);
            }
            return created;
        } catch (DuplicateKeyException concurrentInsert) {
            AgentLegacyMigrationGate concurrent = mapper.selectForUpdate(readerKey);
            if (concurrent == null) {
                throw concurrentInsert;
            }
            return concurrent;
        }
    }

    private void update(AgentLegacyMigrationGate gate) {
        if (mapper.updateGate(gate) != 1) {
            throw new IllegalStateException("Unable to update legacy migration gate " + gate.getReaderKey());
        }
    }

    private GateSnapshot emptySnapshot(String readerKey, LocalDateTime now) {
        AgentLegacyMigrationGate gate = new AgentLegacyMigrationGate();
        gate.setReaderKey(readerKey);
        gate.setTargetRemovalVersion(targetRemovalVersion);
        gate.setObservationWindowDays(observationWindowDays);
        gate.setReadHitCount(0L);
        gate.setSourceItemHitCount(0L);
        gate.setPendingSourceCount(0L);
        return snapshot(gate, now);
    }

    private GateSnapshot snapshot(AgentLegacyMigrationGate gate, LocalDateTime now) {
        int days = gate.getObservationWindowDays() == null
                ? observationWindowDays : Math.max(1, gate.getObservationWindowDays());
        String removalVersion = gate.getTargetRemovalVersion() == null
                ? targetRemovalVersion : gate.getTargetRemovalVersion().trim();
        long pending = safe(gate.getPendingSourceCount());
        LocalDateTime zeroSince = gate.getZeroInventorySince();
        LocalDateTime earliestRemovalAt = zeroSince == null ? null : zeroSince.plusDays(days);
        List<String> blockers = new ArrayList<>();
        if (pending > 0L) {
            blockers.add("pending_sources");
        }
        if (zeroSince == null) {
            blockers.add("inventory_observation_not_started");
        } else if (earliestRemovalAt != null && now.isBefore(earliestRemovalAt)) {
            blockers.add("observation_window_not_elapsed");
        }
        if (removalVersion.isBlank()) {
            blockers.add("target_removal_version_not_configured");
        }
        return new GateSnapshot(
                gate.getReaderKey(), removalVersion, days,
                safe(gate.getReadHitCount()), safe(gate.getSourceItemHitCount()), gate.getLastReadHitAt(),
                pending, gate.getLastInventoryAt(), zeroSince, earliestRemovalAt,
                blockers.isEmpty(), List.copyOf(blockers));
    }

    private String requireReaderKey(String readerKey) {
        String normalized = readerKey == null ? "" : readerKey.trim().toLowerCase(Locale.ROOT);
        if (!List.of(LEGACY_HISTORY_READER, LEGACY_CHECKPOINT_READER).contains(normalized)) {
            throw new IllegalArgumentException("Unknown legacy reader key: " + readerKey);
        }
        return normalized;
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record GateSnapshot(String readerKey,
                               String targetRemovalVersion,
                               int observationWindowDays,
                               long readHitCount,
                               long sourceItemHitCount,
                               LocalDateTime lastReadHitAt,
                               long pendingSourceCount,
                               LocalDateTime lastInventoryAt,
                               LocalDateTime zeroInventorySince,
                               LocalDateTime earliestRemovalAt,
                               boolean readyForRemoval,
                               List<String> blockers) {
        public GateSnapshot {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }
}
