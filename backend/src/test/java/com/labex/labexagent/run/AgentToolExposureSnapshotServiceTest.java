package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.labex.entity.AgentRunEvent;
import com.labex.labexagent.runtime.ToolExposureSnapshot;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.mapper.AgentRunEventMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentToolExposureSnapshotServiceTest {
    private static final Gson GSON = new Gson();

    @Test
    void restoresTheLatestMatchingNativeExposureWithoutReadingAnotherProfileOrMode() {
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        ToolExposureSnapshot expected = ToolExposureSnapshot.live(AgentRuntimeProfile.LABEX_NATIVE, "build",
                List.of(ToolDefinition.builder().name("read_file").description("read").build()), List.of());
        AgentRunEvent matching = event(91L, expected);
        ToolExposureSnapshot wrongMode = ToolExposureSnapshot.live(AgentRuntimeProfile.LABEX_NATIVE, "plan",
                List.of(ToolDefinition.builder().name("grep").description("grep").build()), List.of());
        when(events.selectList(any())).thenReturn(List.of(matching, event(90L, wrongMode)));
        AgentToolExposureSnapshotService service = new AgentToolExposureSnapshotService(events);

        var restored = service.findLatest(71L, AgentRuntimeProfile.LABEX_NATIVE, "build");

        assertTrue(restored.isPresent());
        assertEquals(expected.schemaFingerprint(), restored.orElseThrow().schemaFingerprint());
        assertEquals(List.of("read_file"), restored.orElseThrow().toToolDefinitions().stream()
                .map(ToolDefinition::getName).toList());
    }

    @Test
    void preservesNullableJsonSchemaValuesAcrossDurablePayloadRoundTrip() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("default", null);
        ToolExposureSnapshot snapshot = ToolExposureSnapshot.live(AgentRuntimeProfile.LABEX_NATIVE, "build",
                List.of(new ToolDefinition("inspect", "Inspect schema", schema)), List.of());

        ToolExposureSnapshot restored = ToolExposureSnapshot.fromPayload(snapshot.toPayload()).orElseThrow();
        Map<String, Object> restoredSchema = restored.toToolDefinitions().get(0).getInputSchema();

        assertTrue(restoredSchema.containsKey("default"));
        assertNull(restoredSchema.get("default"));
    }

    @Test
    void ignoresMalformedOrMismatchedExposurePayloads() {
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentRunEvent malformed = new AgentRunEvent();
        malformed.setTaskId(71L);
        malformed.setEventType("TOOL_EXPOSURE");
        malformed.setPayload("not-json");
        when(events.selectList(any())).thenReturn(List.of(malformed));
        AgentToolExposureSnapshotService service = new AgentToolExposureSnapshotService(events);

        assertTrue(service.findLatest(71L, AgentRuntimeProfile.LABEX_NATIVE, "build").isEmpty());
    }

    private static AgentRunEvent event(long sequence, ToolExposureSnapshot snapshot) {
        AgentRunEvent event = new AgentRunEvent();
        event.setTaskId(71L);
        event.setSequenceNumber(sequence);
        event.setEventType("TOOL_EXPOSURE");
        event.setPayload(GSON.toJson(snapshot.toPayload()));
        return event;
    }
}

