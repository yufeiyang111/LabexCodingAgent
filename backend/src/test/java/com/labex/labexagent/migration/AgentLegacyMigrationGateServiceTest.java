package com.labex.labexagent.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentLegacyMigrationGate;
import com.labex.mapper.AgentLegacyMigrationGateMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class AgentLegacyMigrationGateServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-08-05T01:00:00Z");

    @Test
    void readerHitResetsZeroInventoryObservationAndIncrementsDurableCounters() {
        AgentLegacyMigrationGateMapper mapper = mock(AgentLegacyMigrationGateMapper.class);
        AgentLegacyMigrationGate gate = gate(AgentLegacyMigrationGateService.LEGACY_HISTORY_READER);
        gate.setReadHitCount(2L);
        gate.setSourceItemHitCount(7L);
        gate.setZeroInventorySince(LocalDateTime.of(2026, 8, 1, 9, 0));
        when(mapper.selectForUpdate(gate.getReaderKey())).thenReturn(gate);
        when(mapper.updateGate(gate)).thenReturn(1);
        AgentLegacyMigrationGateService service = service(mapper, NOW);

        AgentLegacyMigrationGateService.GateSnapshot snapshot = service.recordReaderHit(gate.getReaderKey(), 4L);

        assertThat(snapshot.readHitCount()).isEqualTo(3L);
        assertThat(snapshot.sourceItemHitCount()).isEqualTo(11L);
        assertThat(snapshot.zeroInventorySince()).isNull();
        assertThat(snapshot.lastReadHitAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZONE));
        assertThat(snapshot.readyForRemoval()).isFalse();
        assertThat(snapshot.blockers()).contains("inventory_observation_not_started");
        verify(mapper).updateGate(gate);
    }

    @Test
    void zeroInventoryMustRemainHitFreeForTheConfiguredWindowBeforeRemovalIsReady() {
        AgentLegacyMigrationGateMapper mapper = mock(AgentLegacyMigrationGateMapper.class);
        AgentLegacyMigrationGate gate = gate(AgentLegacyMigrationGateService.LEGACY_CHECKPOINT_READER);
        gate.setPendingSourceCount(3L);
        when(mapper.selectForUpdate(gate.getReaderKey())).thenReturn(gate);
        when(mapper.updateGate(gate)).thenReturn(1);
        AgentLegacyMigrationGateService service = service(mapper, NOW);

        AgentLegacyMigrationGateService.GateSnapshot observing = service.refreshInventory(gate.getReaderKey(), 0L);

        assertThat(observing.zeroInventorySince()).isEqualTo(LocalDateTime.ofInstant(NOW, ZONE));
        assertThat(observing.readyForRemoval()).isFalse();
        assertThat(observing.blockers()).contains("observation_window_not_elapsed");

        gate.setZeroInventorySince(LocalDateTime.ofInstant(NOW.minusSeconds(15L * 24 * 60 * 60), ZONE));
        when(mapper.selectById(gate.getReaderKey())).thenReturn(gate);
        AgentLegacyMigrationGateService.GateSnapshot ready = service.snapshot(gate.getReaderKey());

        assertThat(ready.readyForRemoval()).isTrue();
        assertThat(ready.earliestRemovalAt()).isBefore(LocalDateTime.ofInstant(NOW, ZONE));
        assertThat(ready.targetRemovalVersion()).isEqualTo("1.1.0");
    }

    private AgentLegacyMigrationGateService service(AgentLegacyMigrationGateMapper mapper, Instant now) {
        return new AgentLegacyMigrationGateService(
                mapper, "1.1.0", 14, Clock.fixed(now, ZONE));
    }

    private AgentLegacyMigrationGate gate(String readerKey) {
        AgentLegacyMigrationGate gate = new AgentLegacyMigrationGate();
        gate.setReaderKey(readerKey);
        gate.setTargetRemovalVersion("1.1.0");
        gate.setObservationWindowDays(14);
        gate.setReadHitCount(0L);
        gate.setSourceItemHitCount(0L);
        gate.setPendingSourceCount(0L);
        return gate;
    }
}
