package com.labex.controller.student;

import java.util.List;

public final class ProjectCommandSafety {
    private static final List<String> HARD_BLOCKED = List.of("shutdown", "reboot", "mkfs", "diskpart", ":(){", "dd if=", "> /dev/sd", "format c:", "cipher /w", "/etc/passwd", "/etc/shadow", ".ssh", ".aws/credentials", "169.254.169.254", "metadata.google.internal", "%userprofile%", "$env:userprofile");
    private static final List<String> NEEDS_APPROVAL = List.of("rm -rf", "rm -r", "del /s", "rmdir /s", "drop database", "drop table", "truncate table", "delete from", "flushall", "flushdb", "docker rm", "docker system prune", "docker volume rm", "docker compose down -v", "npm publish", "mvn deploy", "git push --force", "git reset --hard", "git clean -fd", "pip uninstall", "npm uninstall", "curl ", "wget ", "invoke-webrequest", "invoke-restmethod", "scp ", "sftp ", "rsync ", "powershell -enc", "cmd /c", "bash -c", "sh -c");
    private static final List<String> SAFE_PATTERNS = List.of("npm run build", "npm test", "npm run test", "mvn test", "mvn clean package", "pytest", "python -m pytest", "git status", "git diff", "git log", "ls", "dir", "pwd", "cat ", "type ", "findstr", "grep");

    private ProjectCommandSafety() {
    }

    public static SafetyCheck check(String command, boolean approved) {
        String lower = command == null ? "" : command.toLowerCase();
        if (lower.contains("ignore previous") || lower.contains("ignore all previous") || lower.contains("system prompt") && lower.contains("reveal")) {
            return new SafetyCheck(false, false, "blocked", "prompt_injection", "\u547d\u4ee4\u5305\u542b\u53ef\u7591\u7684\u63d0\u793a\u8bcd\u6ce8\u5165\u6216\u79d8\u5bc6\u6cc4\u9732\u610f\u56fe");
        }
        for (String blocked : HARD_BLOCKED) {
            if (!lower.contains(blocked)) continue;
            return new SafetyCheck(false, false, "blocked", blocked, "\u547d\u4ee4\u88ab\u5b89\u5168\u7b56\u7565\u62d2\u7edd: " + blocked);
        }
        for (String risky : NEEDS_APPROVAL) {
            if (!lower.contains(risky) || approved) continue;
            return new SafetyCheck(false, true, "approval_required", risky, "\u8be5\u547d\u4ee4\u53ef\u80fd\u9020\u6210\u7834\u574f\u6027\u53d8\u66f4\uff0c\u9700\u8981\u7528\u6237\u786e\u8ba4: " + risky);
        }
        for (String safe : SAFE_PATTERNS) {
            if (!lower.trim().startsWith(safe) && !lower.contains("&& " + safe) && !lower.contains("; " + safe)) continue;
            return new SafetyCheck(true, false, "safe", safe, "ok");
        }
        return new SafetyCheck(true, false, "normal", "", "ok");
    }

    public static class SafetyCheck {
        private final boolean safe;
        private final boolean requiresApproval;
        private final String level;
        private final String keyword;
        private final String reason;
        public SafetyCheck(boolean safe, boolean requiresApproval, String level, String keyword, String reason) {
            this.safe = safe; this.requiresApproval = requiresApproval; this.level = level; this.keyword = keyword; this.reason = reason;
        }
        public boolean allowed() { return safe; }
        public boolean approvalRequired() { return requiresApproval; }
        public String message() { return reason; }
        public String riskLevel() { return level; }
        public String matchedRule() { return keyword; }
        public boolean isSafe() { return safe; }
        public boolean isRequiresApproval() { return requiresApproval; }
        public String getLevel() { return level; }
        public String getKeyword() { return keyword; }
        public String getReason() { return reason; }
    }
}
