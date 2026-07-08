package com.labex.labexagent.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.labexagent.lsp.LspSessionManager;
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
    private final LspSessionManager lspSessionManager;

    public AgentPostEditHookService(LspSessionManager lspSessionManager) {
        this.lspSessionManager = lspSessionManager;
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
        List<String> suggestions = new ArrayList<>();

        for (String relative : changedFiles) {
            try {
                Path file = ToolSupport.resolve(context, relative);
                if (!Files.isRegularFile(file) || !isDiagnosable(file)) {
                    continue;
                }
                DiagnosticSummary summary = diagnose(context.getWorkspaceRoot(), file);
                totalErrors += summary.errors();
                totalWarnings += summary.warnings();
                out.append("- ").append(relative).append(": ")
                        .append(summary.source()).append(" diagnostics ")
                        .append(summary.errors()).append(" error(s), ")
                        .append(summary.warnings()).append(" warning(s)\n");
                for (String line : summary.lines().stream().limit(8).toList()) {
                    out.append("  - ").append(line).append('\n');
                }
            } catch (Exception e) {
                out.append("- ").append(relative).append(": hook failed (").append(e.getMessage()).append(")\n");
            }
            suggestedCommand(context.getWorkspaceRoot(), relative).ifPresent(suggestions::add);
        }

        List<String> uniqueSuggestions = new ArrayList<>(new LinkedHashSet<>(suggestions));
        if (!uniqueSuggestions.isEmpty()) {
            out.append("- suggested_verification:\n");
            for (String command : uniqueSuggestions.stream().limit(4).toList()) {
                out.append("  - ").append(command).append('\n');
            }
        }
        if (totalErrors > 0) {
            out.append("- action_required: fix diagnostics before final response.\n");
        }
        return new HookReport(changedFiles, totalErrors, totalWarnings, uniqueSuggestions, out.toString());
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
            return new DiagnosticSummary("lsp:" + lsp.languageId(), errors, warnings, lines);
        }
        return new DiagnosticSummary(
                "lsp-unavailable",
                1,
                0,
                List.of("[LSP UNAVAILABLE] " + lsp.message()
                        + ". Run scripts/setup-lsp.ps1, then restart backend.")
        );
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

    private record DiagnosticSummary(String source, int errors, int warnings, List<String> lines) {}

    public record HookReport(List<String> changedFiles,
                             int errorCount,
                             int warningCount,
                             List<String> suggestedCommands,
                             String content) {
        public static HookReport empty() {
            return new HookReport(List.of(), 0, 0, List.of(), "");
        }
    }
}
