package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 契约：网络访问默认开启后，执行链不再为网络命令创建任何审批；
 * 破坏性命令审批仍然持久化存在，普通命令继续进入通用 PermissionService 评估。
 */
class AgentLoopEngineNetworkApprovalMergingTest {

    private final String source = readSource("AgentLoopEngine.java");

    @Test
    void networkCommandsRunWithoutAnyNetworkApprovalGate() {
        assertFalse(source.contains("createNetworkApproval"),
                "network approval creation must be removed from the exec tool path");
        assertFalse(source.contains("maybeRequestNetworkAfterFailure"),
                "offline network-retry approval must be removed from the exec tool path");
        assertFalse(source.contains("isNetworkCommandClassification"),
                "legacy network classification gate must be removed");
        assertFalse(source.contains("hasApprovedOfflineRetryGrant"),
                "legacy offline-retry grant check must be removed from the exec tool path");
        assertTrue(source.contains("// 网络访问默认开启：不再为网络命令创建一次性审批"),
                "the network-enabled-by-default policy must be documented at the gate");
    }

    @Test
    void destructiveCommandApprovalRemainsDurableAfterTheClassificationGate() {
        int classification = source.indexOf("CommandClassification classification = this.commandClassification(name, args, ctx);");
        int requiresApproval = source.indexOf("if (classification.requiresApproval()");
        int commandApproval = source.indexOf("return this.createCommandApproval(ctx, name, classification, timeout, toolCallId);");
        int genericPermission = source.indexOf("PermissionService.PermissionEvaluation eval");

        assertTrue(classification >= 0, "command classification must run inside the command policy gate");
        assertTrue(requiresApproval > classification, "approval decision must follow classification");
        assertTrue(commandApproval > requiresApproval, "destructive command approval must be durable and explicit");
        assertTrue(genericPermission > commandApproval, "ordinary commands continue to the generic PermissionService evaluation");
    }

    @Test
    void commandApprovalKeepsNetworkGrantAndRecordsWhetherTheToolIsLongRunning() {
        assertTrue(source.contains("\"timeout=\" + timeout + \";longRunning=\" + this.isPreviewTool(toolName) + \";network=\""),
                "command approval options must keep the network grant flag and the actual lifecycle type");
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
