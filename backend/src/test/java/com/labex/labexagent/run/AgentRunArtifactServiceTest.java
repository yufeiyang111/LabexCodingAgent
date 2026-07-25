package com.labex.labexagent.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
class AgentRunArtifactServiceTest {
 @Test void hashesArtifactContentDeterministically() { assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", AgentRunArtifactService.sha256("hello")); }
}
