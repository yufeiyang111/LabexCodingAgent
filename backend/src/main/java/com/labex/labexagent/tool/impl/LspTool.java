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
import java.util.List;

@Component
public class LspTool implements AgentTool {
    private final LspSessionManager lspSessionManager;

    public LspTool(LspSessionManager lspSessionManager) {
        this.lspSessionManager = lspSessionManager;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("lsp")
                .description("Real language-server operations only. Supports status, documentSymbol, diagnostics, definition, references and hover. No static parser fallback is used.")
                .stringProperty("action", "one of: status, documentSymbol, diagnostics, definition, references, hover", true)
                .stringProperty("file_path", "target file path", true)
                .intProperty("line", "1-based line number for definition/references/hover", false)
                .intProperty("character", "1-based column for definition/references/hover, default 1", false)
                .intProperty("max_results", "max locations/symbols, default 50", false)
                .stringProperty("include_declaration", "references only: true/false, default true", false)
                .build();
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        String action = ToolSupport.stringArgMulti(args, "", "action", "operation");
        String pathArg = ToolSupport.stringArgMulti(args, "", "file_path", "path");
        if (action.isBlank()) {
            return ToolResult.failed("action is required");
        }
        if (pathArg.isBlank()) {
            return ToolResult.failed("file_path is required");
        }

        Path file = resolveFile(context, pathArg);
        if (file == null) {
            return ToolResult.failed("file not found: " + pathArg);
        }
        Path root = context.getWorkspaceRoot();

        return switch (action.toLowerCase()) {
            case "status", "server_status" -> doStatus(root, file);
            case "documentsymbol", "document_symbol", "symbols" -> doDocumentSymbol(root, file, pathArg, args);
            case "diagnostics", "diagnostic" -> doDiagnostics(root, file, pathArg);
            case "definition", "def", "goto" -> doDefinition(root, file, pathArg, args);
            case "references", "ref", "refs" -> doReferences(root, file, pathArg, args);
            case "hover", "info" -> doHover(root, file, pathArg, args);
            default -> ToolResult.failed("Unknown action: " + action + ". Use: status, documentSymbol, diagnostics, definition, references, hover");
        };
    }

    private ToolResult doStatus(Path root, Path file) {
        return ToolResult.ok("LSP status: " + lspSessionManager.status(root, file).orElse("unknown"));
    }

    private ToolResult doDocumentSymbol(Path root, Path file, String pathArg, JsonObject args) {
        LspSessionManager.LspSymbolsResult result = lspSessionManager.documentSymbols(root, file);
        if (!result.available()) {
            return unavailable(result.message());
        }
        int max = Math.min(300, Math.max(1, ToolSupport.intArg(args, "max_results", 120)));
        List<String> symbols = result.symbols().stream().limit(max).toList();
        if (symbols.isEmpty()) {
            return ToolResult.ok("Real LSP returned no document symbols in " + pathArg + " (" + result.languageId() + ")");
        }
        return ToolResult.ok("LSP symbols in " + pathArg + " (" + result.languageId() + "):\n" + String.join("\n", symbols));
    }

    private ToolResult doDiagnostics(Path root, Path file, String pathArg) {
        LspSessionManager.LspDiagnosticsResult result = lspSessionManager.diagnostics(root, file);
        if (!result.available()) {
            return unavailable(result.message());
        }
        if (result.diagnostics().isEmpty()) {
            return ToolResult.ok("LSP diagnostics clean for " + pathArg + " (" + result.languageId() + ")");
        }
        StringBuilder out = new StringBuilder();
        out.append("LSP diagnostics for ").append(pathArg).append(" (").append(result.languageId()).append("):\n");
        for (Diagnostic diagnostic : result.diagnostics()) {
            int line = diagnostic.getRange() == null ? 1 : diagnostic.getRange().getStart().getLine() + 1;
            out.append("- line ").append(line)
                    .append(" [").append(diagnostic.getSeverity()).append("] ")
                    .append(diagnostic.getMessage()).append('\n');
        }
        return ToolResult.ok(out.toString());
    }

    private ToolResult doDefinition(Path root, Path file, String pathArg, JsonObject args) {
        PositionInput position = positionInput(args);
        if (!position.valid()) {
            return ToolResult.failed("line is required for real LSP definition lookup; character is optional and defaults to 1.");
        }
        LspSessionManager.LspLocationsResult result = lspSessionManager.definition(root, file, position.zeroBasedLine(), position.zeroBasedCharacter());
        return locationsResult("definition", result, pathArg, position, args);
    }

    private ToolResult doReferences(Path root, Path file, String pathArg, JsonObject args) {
        PositionInput position = positionInput(args);
        if (!position.valid()) {
            return ToolResult.failed("line is required for real LSP references lookup; character is optional and defaults to 1.");
        }
        String includeRaw = ToolSupport.stringArgMulti(args, "true", "include_declaration", "includeDeclaration");
        boolean includeDeclaration = !"false".equalsIgnoreCase(includeRaw);
        LspSessionManager.LspLocationsResult result = lspSessionManager.references(root, file, position.zeroBasedLine(), position.zeroBasedCharacter(), includeDeclaration);
        return locationsResult("references", result, pathArg, position, args);
    }

    private ToolResult doHover(Path root, Path file, String pathArg, JsonObject args) {
        PositionInput position = positionInput(args);
        if (!position.valid()) {
            return ToolResult.failed("line is required for real LSP hover; character is optional and defaults to 1.");
        }
        LspSessionManager.LspHoverResult result = lspSessionManager.hover(root, file, position.zeroBasedLine(), position.zeroBasedCharacter());
        if (!result.available()) {
            return unavailable(result.message());
        }
        if (result.content().isBlank()) {
            return ToolResult.ok("Real LSP returned no hover content for " + pathArg + ":" + position.line() + ":" + position.character() + " (" + result.languageId() + ")");
        }
        return ToolResult.ok("LSP hover for " + pathArg + ":" + position.line() + ":" + position.character() + " (" + result.languageId() + "):\n"
                + ToolSupport.limit(result.content(), 4000));
    }

    private ToolResult locationsResult(String label,
                                       LspSessionManager.LspLocationsResult result,
                                       String pathArg,
                                       PositionInput position,
                                       JsonObject args) {
        if (!result.available()) {
            return unavailable(result.message());
        }
        int max = Math.min(200, Math.max(1, ToolSupport.intArg(args, "max_results", 50)));
        List<String> locations = result.locations().stream().filter(s -> s != null && !s.isBlank()).limit(max).toList();
        if (locations.isEmpty()) {
            return ToolResult.ok("Real LSP returned no " + label + " for " + pathArg + ":" + position.line() + ":" + position.character()
                    + " (" + result.languageId() + ")");
        }
        return ToolResult.ok("LSP " + label + " for " + pathArg + ":" + position.line() + ":" + position.character()
                + " (" + result.languageId() + "):\n- " + String.join("\n- ", locations));
    }

    private PositionInput positionInput(JsonObject args) {
        int line = ToolSupport.intArg(args, "line", 0);
        int character = ToolSupport.intArg(args, "character", ToolSupport.intArg(args, "column", 1));
        if (line <= 0) {
            return new PositionInput(0, Math.max(1, character), false);
        }
        return new PositionInput(line, Math.max(1, character), true);
    }

    private ToolResult unavailable(String message) {
        return ToolResult.failed("Real LSP unavailable: " + message
                + "\nRun scripts/setup-lsp.ps1, then restart backend.");
    }

    private Path resolveFile(AgentContext context, String pathArg) {
        Path file = ToolSupport.resolve(context, pathArg);
        return Files.isRegularFile(file) ? file : null;
    }

    private record PositionInput(int line, int character, boolean valid) {
        int zeroBasedLine() {
            return Math.max(0, line - 1);
        }

        int zeroBasedCharacter() {
            return Math.max(0, character - 1);
        }
    }
}
