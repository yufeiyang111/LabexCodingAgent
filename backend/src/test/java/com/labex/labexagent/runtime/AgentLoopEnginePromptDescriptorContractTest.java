package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentLoopEnginePromptDescriptorContractTest {

    @Test
    void projectsSystemPromptFromTheActiveWorkerDescriptor() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/AgentLoopEngine.java"))
                .replace("\r\n", "\n").replace("\r", "\n");

        assertThat(source)
                .contains("this.buildSystemPrompt(project, toolDefinitions, visibleLanguage)")
                .contains("LabexSystemPrompt.buildSystemPrompt(project, toolDefinitions, visibleLanguage,")
                .contains("this.sandboxWorker.shellDescriptor(run)")
                .contains("this.shellPromptDescriptor(project)")
                .contains("this.executionProperties.getPermissionProfile()")
                .contains("conv.getConversationId()")
                .contains("PromptCacheKeyFactory.forConversation(studentId, modelConfig.getConfigId(),")
                .doesNotContain("buildRuntimeContext(");
    }
}
