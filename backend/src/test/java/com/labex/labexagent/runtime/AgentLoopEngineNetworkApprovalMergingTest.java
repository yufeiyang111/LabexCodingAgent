package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 契约：网络命令（npm install 等）在用户批准网络审批后不得再次弹命令审批。
 * 一次批准 = 该命令带网络执行一次。
 */
class AgentLoopEngineNetworkApprovalMergingTest {

    private final String source = readSource("AgentLoopEngine.java");

    @Test
    void opencodeShellBypassesLegacyNetworkApprovalButKeepsDestructiveApprovalDurable() {
        int shellProfile = source.indexOf("boolean opencodeShell = this.usesOpenCodeShellContract(name);");
        int networkGate = source.indexOf("if (!opencodeShell && classification != null");
        int commandGate = source.indexOf("if (classification.requiresApproval()\n                        && !approvedOfflineRetry");
        int commandApproval = source.indexOf("return this.createCommandApproval(ctx, name, classification, timeout, toolCallId);");
        int genericPermission = source.indexOf("PermissionService.PermissionEvaluation eval");

        assertTrue(shellProfile >= 0, "opencode Shell profile must be calculated before command gates");
        assertTrue(networkGate > shellProfile, "legacy network approval must be conditional on non-opencode shell");
        assertTrue(commandGate > networkGate, "destructive command approval remains after the network gate");
        assertTrue(commandApproval > commandGate, "destructive command approval must be durable and explicit");
        assertTrue(genericPermission > commandGate, "ordinary commands continue to the generic PermissionService evaluation");
        assertTrue(source.contains("this.usesOpenCodeShellContract(toolName)"),
                "offline network-retry approval must also be bypassed for opencode shell");
        assertFalse(source.contains("Thread.sleep(28L)"));
    }

    private String readSource(String fileName) {
        try {
            return Files.readString(Path.of("src/main/java/com/labex/labexagent/runtime/" + fileName))
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
