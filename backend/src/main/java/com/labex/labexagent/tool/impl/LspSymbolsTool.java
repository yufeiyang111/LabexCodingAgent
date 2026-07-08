package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class LspSymbolsTool implements AgentTool {
    private final LspSessionManager lspSessionManager;

    public LspSymbolsTool(LspSessionManager lspSessionManager) {
        this.lspSessionManager = lspSessionManager;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("lsp_symbols")
                .description("Use the real language server to extract document symbols. Fails if the language server is not configured.")
                .stringProperty("file_path", "file path to analyze", true)
                .intProperty("max_symbols", "maximum symbol count, default 120", false)
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
        LspSessionManager.LspSymbolsResult result = lspSessionManager.documentSymbols(context.getWorkspaceRoot(), file);
        if (!result.available()) {
            return ToolResult.failed("Real LSP unavailable: " + result.message()
                    + "\nRun scripts/setup-lsp.ps1, then restart backend.");
        }
        int max = Math.min(300, Math.max(1, ToolSupport.intArg(args, "max_symbols", 120)));
        var symbols = result.symbols().stream().limit(max).toList();
        if (symbols.isEmpty()) {
            return ToolResult.ok("Real LSP returned no symbols for " + pathArg + " (" + result.languageId() + ")");
        }
        return ToolResult.ok("LSP symbols for " + pathArg + " (" + result.languageId() + "):\n"
                + String.join("\n", symbols));
    }
}
