package com.labex.labexagent.commandsecurity;

/** 将命令策略原因转换为不泄露原始命令、且能指导 Agent 恢复的提示。 */
public final class CommandPolicyMessage {
    private CommandPolicyMessage() {
    }

    public static String forReason(CommandReasonCode reason) {
        if (reason == null) {
            return "命令不符合当前工作区的受限直接命令策略。";
        }
        return switch (reason) {
            case SHELL_OPERATOR, REDIRECTION, COMMAND_SUBSTITUTION,
                 VARIABLE_EXPANSION, WINDOWS_VARIABLE_EXPANSION ->
                    "当前命令工具只接受一条受限直接命令，不支持管道、重定向、变量、命令替换或多命令串联；请拆分为多个独立工具调用。";
            case NETWORK_COMMAND, NETWORK_URL ->
                    "该命令涉及网络访问，需要经过一次性用户审批后执行；批准后会启用沙箱网络。";
            case UNRECOGNIZED_COMMAND ->
                    "该命令不在内置命令白名单内，需要经过一次性用户审批后才能执行。";
            case SHELL_COMMAND_STRING, POWERSHELL_COMMAND,
                 POWERSHELL_ENCODED_COMMAND, ENCODED_EXECUTION ->
                    "不允许通过 Shell、PowerShell 字符串或编码内容间接执行命令。";
            case HARD_BLOCKED_COMMAND ->
                    "该命令属于工作区硬阻断命令，不能执行。";
            case MUTATING_COMMAND ->
                    "该命令会删除或破坏工作区内容（删除文件、丢弃改动、容器操作），需要经过一次性用户审批后才能执行。";
            default -> "命令不符合当前工作区的受限直接命令策略。";
        };
    }
}
