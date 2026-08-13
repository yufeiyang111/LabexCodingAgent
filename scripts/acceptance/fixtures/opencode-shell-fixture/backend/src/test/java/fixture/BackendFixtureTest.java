package fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BackendFixtureTest {
    @Test
    void writesARealVerificationMarker() throws Exception {
        Path marker = Path.of("target", "backend-fixture-test.marker");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "backend fixture test passed\n", StandardCharsets.UTF_8);

        assertTrue(Files.exists(marker));
        assertEquals("backend fixture test passed\n", Files.readString(marker, StandardCharsets.UTF_8));
    }
}
