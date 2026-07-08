package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.eclipse.lsp4j.Diagnostic;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DiagnosticsTool implements AgentTool {
    private final LspSessionManager lspSessionManager;

    public DiagnosticsTool(LspSessionManager lspSessionManager) {
        this.lspSessionManager = lspSessionManager;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("diagnostics")
                .description("Run real language-server diagnostics for a file. Fails if the language server is not configured.")
                .stringProperty("file_path", "file path to analyze", true)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String pathArg = ToolSupport.stringArgMulti(args, "", "file_path", "path");
        if (pathArg.isBlank()) {
            return ToolResult.failed("file_path is required");
        }
        Path file = ToolSupport.resolve(context, pathArg);
        if (!Files.isRegularFile(file)) {
            return ToolResult.failed("file not found: " + pathArg);
        }

        LspSessionManager.LspDiagnosticsResult result = lspSessionManager.diagnostics(context.getWorkspaceRoot(), file);
        if (!result.available()) {
            return ToolResult.failed("Real LSP unavailable: " + result.message()
                    + "\nRun scripts/setup-lsp.ps1, then restart backend.");
        }
        if (result.diagnostics().isEmpty()) {
            return ToolResult.ok("LSP diagnostics clean for " + pathArg + " (" + result.languageId() + ")");
        }

        long errors = result.diagnostics().stream().filter(this::isError).count();
        long warnings = result.diagnostics().size() - errors;
        StringBuilder out = new StringBuilder();
        out.append("LSP diagnostics for ").append(pathArg).append(" (").append(result.languageId()).append("):\n");
        out.append("Found ").append(errors).append(" error(s), ").append(warnings).append(" warning(s)\n\n");
        for (Diagnostic diagnostic : result.diagnostics()) {
            int line = diagnostic.getRange() == null ? 1 : diagnostic.getRange().getStart().getLine() + 1;
            out.append("- [").append(diagnostic.getSeverity()).append("] line ").append(line)
                    .append(": ").append(diagnostic.getMessage()).append('\n');
        }
        return ToolResult.ok(out.toString());
    }

    private boolean isError(Diagnostic diagnostic) {
        return diagnostic.getSeverity() != null && diagnostic.getSeverity().getValue() == 1;
    }
}
