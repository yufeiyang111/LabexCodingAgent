package com.labex.labexagent.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.run.AgentRunArtifactService;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.eclipse.lsp4j.Diagnostic;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AgentPostEditHookService {
    private static final int MAX_DIAGNOSTIC_FILES = 3;
    private static final int MAX_DIAGNOSTIC_LINES_PER_FILE = 8;

    private final LspSessionManager lspSessionManager;
    private final AgentRunArtifactService artifactService;

    public AgentPostEditHookService(LspSessionManager lspSessionManager) {
        this(lspSessionManager, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentPostEditHookService(LspSessionManager lspSessionManager, AgentRunArtifactService artifactService) {
        this.lspSessionManager = lspSessionManager;
        this.artifactService = artifactService;
    }

    public HookReport afterTool(AgentContext context, String toolName, JsonObject args, ToolResult result) {
        if (context == null || result == null || !result.isSuccess() || !isWriteTool(toolName)) {
            return HookReport.empty();
        }
        List<String> changedFiles = changedFiles(toolName, args);
        if (changedFiles.isEmpty()) {
            return HookReport.empty();
        }

        StringBuilder out = new StringBuilder();
        out.append("\n\n[Post-edit hooks]\n");
        int totalErrors = 0;
        int totalWarnings = 0;
        int checked = 0;
        int skipped = 0;
        int unavailable = 0;
        List<String> suggestions = new ArrayList<>();
        List<String> unresolvedRisks = new ArrayList<>();

        for (String relative : changedFiles) {
            suggestedCommand(context.getWorkspaceRoot(), relative).ifPresent(suggestions::add);
            if (checked >= MAX_DIAGNOSTIC_FILES) {
                skipped++;
                continue;
            }
            try {
                Path file = ToolSupport.resolve(context, relative);
                if (!Files.isRegularFile(file) || !isDiagnosable(file)) {
                    skipped++;
                    continue;
                }
                checked++;
                DiagnosticSummary summary = diagnose(context.getWorkspaceRoot(), file);
                if (!summary.available()) {
                    unavailable++;
                    unresolvedRisks.add("LSP unavailable: " + relative + " (" + summary.message() + ")");
                    out.append("- ").append(relative).append(": ")
                            .append(summary.source()).append(" unavailable (")
                            .append(summary.message()).append(")\n");
                    continue;
                }
                totalErrors += summary.errors();
                totalWarnings += summary.warnings();
                if (summary.errors() > 0) {
                    unresolvedRisks.add("LSP diagnostics failed: " + relative + " (" + summary.errors() + " error(s))");
                }
                out.append("- ").append(relative).append(": ")
                        .append(summary.source()).append(" diagnostics ")
                        .append(summary.errors()).append(" error(s), ")
                        .append(summary.warnings()).append(" warning(s)\n");
                for (String line : summary.lines().stream().limit(MAX_DIAGNOSTIC_LINES_PER_FILE).toList()) {
                    out.append("  - ").append(line).append('\n');
                }
            } catch (Exception e) {
                unavailable++;
                unresolvedRisks.add("Diagnostics unavailable: " + relative + " (" + message(e) + ")");
                out.append("- ").append(relative).append(": diagnostics unavailable (")
                        .append(message(e)).append(")\n");
            }
        }
        out.append("- diagnostics: checked ").append(checked)
                .append(", skipped ").append(skipped)
                .append(", unavailable ").append(unavailable);
        if (changedFiles.size() > MAX_DIAGNOSTIC_FILES) {
            out.append(", capped at ").append(MAX_DIAGNOSTIC_FILES);
        }
        out.append('\n');

        List<String> uniqueSuggestions = new ArrayList<>(new LinkedHashSet<>(suggestions));
        if (!uniqueSuggestions.isEmpty()) {
            out.append("- suggested_verification:\n");
            for (String command : uniqueSuggestions.stream().limit(4).toList()) {
                out.append("  - ").append(command).append('\n');
            }
        }
        VerificationStatus status = totalErrors > 0
                ? VerificationStatus.FAIL
                : unavailable > 0
                ? VerificationStatus.UNAVAILABLE
                : checked == 0
                ? VerificationStatus.SKIPPED
                : VerificationStatus.PASS;
        out.append("- status=").append(status.name()).append('\n');
        if (status == VerificationStatus.FAIL || status == VerificationStatus.UNAVAILABLE) {
            out.append("- action_required: diagnostics are not a successful verification; run the suggested compile or test command before final response.\n");
        }
        HookReport report = new HookReport(changedFiles, totalErrors, totalWarnings, uniqueSuggestions,
                unresolvedRisks, status, out.toString());
        persistReport(context, report);
        return report;
    }

    private void persistReport(AgentContext context, HookReport report) {
        if (artifactService == null || context == null || context.getTaskId() == null || report.status() == VerificationStatus.PASS) {
            return;
        }
        try {
            artifactService.record(context.getExecutionFence(), context.getTaskId(),
                    "post_edit_verification", "post-edit", report.content());
        } catch (AgentRunExecutionLeaseService.StaleExecutionFenceException staleFence) {
            // 执行者已失去租约：不允许静默落盘验证证据，向执行循环暴露 typed failure。
            throw staleFence;
        } catch (Exception ignored) {
            // 诊断记录失败不能覆盖实际编辑结果；工具结果仍会附带 hook 状态。
        }
    }

    private DiagnosticSummary diagnose(Path root, Path file) throws Exception {
        LspSessionManager.LspDiagnosticsResult lsp = lspSessionManager.diagnostics(root, file);
        if (lsp.available()) {
            int errors = 0;
            int warnings = 0;
            List<String> lines = new ArrayList<>();
            for (Diagnostic diagnostic : lsp.diagnostics()) {
                String severity = String.valueOf(diagnostic.getSeverity());
                if ("Error".equalsIgnoreCase(severity) || "1".equals(severity)) errors++;
                else warnings++;
                int line = diagnostic.getRange() == null ? 1 : diagnostic.getRange().getStart().getLine() + 1;
                lines.add("[LSP " + severity + "] line " + line + ": " + diagnostic.getMessage());
            }
            return DiagnosticSummary.available("lsp:" + lsp.languageId(), errors, warnings, lines);
        }
        return DiagnosticSummary.unavailable("lsp", lsp.message());
    }

    private List<String> changedFiles(String toolName, JsonObject args) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        String tool = safeTool(toolName);
        if ("write_file".equals(tool) || "edit_file".equals(tool)) {
            String path = ToolSupport.stringArgMulti(args, "", "file_path", "path", "filePath");
            if (!path.isBlank()) paths.add(ToolSupport.normalizeRelativePath(path));
        } else if ("apply_patch".equals(tool) && args != null && args.has("changes") && args.get("changes").isJsonArray()) {
            JsonArray changes = args.getAsJsonArray("changes");
            for (JsonElement element : changes) {
                if (!element.isJsonObject()) continue;
                JsonObject change = element.getAsJsonObject();
                String path = ToolSupport.stringArgMulti(change, "", "file_path", "path", "filePath");
                if (!path.isBlank()) paths.add(ToolSupport.normalizeRelativePath(path));
            }
        }
        paths.removeIf(String::isBlank);
        return new ArrayList<>(paths);
    }

    private java.util.Optional<String> suggestedCommand(Path root, String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java") || Files.exists(root.resolve("pom.xml"))) {
            return java.util.Optional.of(Files.exists(root.resolve("pom.xml")) ? "mvn test -q" : "compile Java project");
        }
        if (lower.endsWith(".vue") || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx") || Files.exists(root.resolve("package.json"))) {
            return java.util.Optional.of(Files.exists(root.resolve("package.json")) ? "npm run test -- --run" : "run frontend tests");
        }
        if (lower.endsWith(".py")) {
            return java.util.Optional.of(Files.exists(root.resolve("pyproject.toml")) || Files.exists(root.resolve("requirements.txt")) ? "python -m pytest" : "run Python tests");
        }
        return java.util.Optional.empty();
    }

    private boolean isWriteTool(String toolName) {
        return Set.of("write_file", "edit_file", "apply_patch").contains(safeTool(toolName));
    }

    private String safeTool(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDiagnosable(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return Set.of(".java", ".js", ".jsx", ".ts", ".tsx", ".vue", ".py", ".json", ".css", ".scss").stream()
                .anyMatch(name::endsWith);
    }

    private String message(Exception error) {
        if (error.getMessage() == null || error.getMessage().isBlank()) {
            return error.getClass().getSimpleName();
        }
        return error.getMessage();
    }

    private record DiagnosticSummary(boolean available, String source, String message, int errors, int warnings, List<String> lines) {
        static DiagnosticSummary available(String source, int errors, int warnings, List<String> lines) {
            return new DiagnosticSummary(true, source, "", errors, warnings, lines);
        }

        static DiagnosticSummary unavailable(String source, String message) {
            return new DiagnosticSummary(false, source, message == null || message.isBlank() ? "LSP did not respond" : message, 0, 0, List.of());
        }
    }

    public enum VerificationStatus {
        PASS, FAIL, UNAVAILABLE, SKIPPED
    }

    public record HookReport(List<String> changedFiles,
                             int errorCount,
                             int warningCount,
                             List<String> suggestedCommands,
                             List<String> unresolvedRisks,
                             VerificationStatus status,
                             String content) {
        public HookReport {
            changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
            suggestedCommands = List.copyOf(suggestedCommands == null ? List.of() : suggestedCommands);
            unresolvedRisks = List.copyOf(unresolvedRisks == null ? List.of() : unresolvedRisks);
            status = status == null ? VerificationStatus.SKIPPED : status;
            content = content == null ? "" : content;
        }

        public static HookReport empty() {
            return new HookReport(List.of(), 0, 0, List.of(), List.of(), VerificationStatus.SKIPPED, "");
        }
    }
}
