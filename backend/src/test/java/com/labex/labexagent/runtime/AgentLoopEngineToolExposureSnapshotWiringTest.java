package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEngineToolExposureSnapshotWiringTest {
    @Test
    void nativeProjectionRestoresAndPersistsItsDurableToolExposureSnapshot() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"));

        assertTrue(source.contains("AgentToolExposureSnapshotService toolExposureSnapshotService"));
        assertTrue(source.contains("this.toolExposureSnapshotService.findLatest"));
        assertTrue(source.contains("this.persistToolExposureSnapshot"));
        assertTrue(source.contains("\"TOOL_EXPOSURE\""));
    }
}
