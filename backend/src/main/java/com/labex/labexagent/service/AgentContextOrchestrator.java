package com.labex.labexagent.service;

import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.lsp.LspSessionManager;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentContextManager;
import com.labex.labexagent.tool.ToolResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentContextOrchestrator {
    private final AgentContextManager contextManager;
    private final ProjectIndexService projectIndexService;
    private final AgentWorkspaceMemoryService workspaceMemoryService;
    private final LspSessionManager lspSessionManager;
    private final ProjectCodeMapService projectCodeMapService;

    public AgentContextOrchestrator(AgentContextManager contextManager,
                                    ProjectIndexService projectIndexService,
                                    AgentWorkspaceMemoryService workspaceMemoryService,
                                    LspSessionManager lspSessionManager,
                                    ProjectCodeMapService projectCodeMapService) {
        this.contextManager = contextManager;
        this.projectIndexService = projectIndexService;
        this.workspaceMemoryService = workspaceMemoryService;
        this.lspSessionManager = lspSessionManager;
        this.projectCodeMapService = projectCodeMapService;
    }

    public ContextBundle buildInitialBundle(StudentProject project,
                                            String activePath,
                                            String activeFileContent,
                                            String toolDefinitions,
                                            String userMessage,
                                            String projectIndexContent,
                                            boolean smallModel,
                                            AgentContext agentContext) {
        String base = contextManager.buildInitialContext(
                project, activePath, activeFileContent, toolDefinitions, userMessage, projectIndexContent, smallModel);
        AgentWorkspaceMemoryService.WorkspaceMemory memory = workspaceMemoryService.readMemory(project);
        String adaptiveIndex = projectIndexService.buildAdaptiveProjectContext(project, userMessage, memory.touchedFiles);
        String repoMap = projectCodeMapService.buildRepoMap(project, userMessage, memory.touchedFiles, smallModel ? 10 : 20);
        String workspaceMemory = workspaceMemoryService.buildMemoryContext(project, userMessage, activePath);
        String workspaceDiagnostics = buildDiagnosticsContext(project, activePath, memory.touchedFiles, smallModel ? 5 : 10);
        String stage = agentContext == null ? "intake" : agentContext.getStage();

        StringBuilder context = new StringBuilder(base);
        context.append("\n<engineering_stage>\n")
                .append("current_stage: ").append(stage).append('\n')
                .append(stageGuidance(stage))
                .append("\n</engineering_stage>\n");
        context.append("\n<adaptive_project_context>\n")
                .append(limit(adaptiveIndex, smallModel ? 8000 : 18000))
                .append("\n</adaptive_project_context>\n");
        context.append("\n<repo_map>\n")
                .append(limit(repoMap, smallModel ? 6000 : 14000))
                .append("\n</repo_map>\n");
        context.append("\n<workspace_memory>\n")
                .append(limit(workspaceMemory, smallModel ? 3000 : 6000))
                .append("\n</workspace_memory>\n");
        context.append("\n<workspace_diagnostics>\n")
                .append(limit(workspaceDiagnostics, smallModel ? 2500 : 5000))
                .append("\n</workspace_diagnostics>\n");

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("stage", stage);
        stats.put("contextChars", context.length());
        stats.put("estimatedTokens", Math.max(1, context.length() / 3));
        stats.put("activePath", activePath == null ? "" : activePath);
        stats.put("projectContextHash", hash(adaptiveIndex));
        stats.put("repoMapHash", hash(repoMap));
        stats.put("workspaceMemoryHash", hash(workspaceMemory));
        stats.put("workspaceDiagnosticsHash", hash(workspaceDiagnostics));
        return new ContextBundle(context.toString(), stats);
    }

    public void afterTool(AgentContext context, String toolName, JsonObject args, ToolResult result) {
        if (context == null || toolName == null || result == null) return;
        workspaceMemoryService.recordToolResult(context, toolName, args, result);
        advanceStage(context, toolName, args, result);
    }

    private void advanceStage(AgentContext context, String toolName, JsonObject args, ToolResult result) {
        String tool = toolName.trim().toLowerCase();
        String stage = context.getStage();
        if (!result.isSuccess()) {
            context.setStage("repair");
            return;
        }
        if ("create_plan".equals(tool) || "plan".equals(tool) || "todo_write".equals(tool)) {
            if ("intake".equals(stage)) context.setStage("explore");
            return;
        }
        if (isReadTool(tool)) {
            if (isManualVerificationRead(context, tool, args, result)) {
                context.incrementVerificationCount();
                context.markChangesVerified();
                context.setStage("verify");
                return;
            }
            if ("intake".equals(stage) || "explore".equals(stage)) context.setStage("design");
            return;
        }
        if (isWriteTool(tool)) {
            context.incrementWriteCount();
            context.markUnverifiedChangeTarget(toolTarget(args));
            context.setStage("implement");
            return;
        }
        if (isVerificationTool(tool)) {
            if (result.isSuccess()) {
                context.incrementVerificationCount();
                context.markChangesVerified();
            }
            context.setStage(result.isSuccess() ? "verify" : "repair");
        }
    }

    private boolean isManualVerificationRead(AgentContext context, String tool, JsonObject args, ToolResult result) {
        if (!context.hasUnverifiedChanges() || !"read_file".equals(tool)) {
            return false;
        }
        String target = toolTarget(args);
        if (!context.matchesUnverifiedChangeTarget(target)) {
            return false;
        }
        String content = result.getContent() == null ? "" : result.getContent();
        return content.contains("[read_file path=") && content.contains("sha256=");
    }

    private String toolTarget(JsonObject args) {
        if (args == null) {
            return "";
        }
        for (String key : List.of("file_path", "path", "target_file", "relativePath")) {
            if (args.has(key) && !args.get(key).isJsonNull()) {
                String value = args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : args.get(key).toString();
                if (value != null && !value.isBlank()) {
                    return value.trim().replace('\\', '/');
                }
            }
        }
        return "";
    }

    private boolean isReadTool(String tool) {
        return "read_file".equals(tool) || "grep".equals(tool) || "glob".equals(tool)
                || "list_files".equals(tool) || "search_code".equals(tool) || "project_overview".equals(tool)
                || "retrieve_context".equals(tool);
    }

    private boolean isWriteTool(String tool) {
        return "write_file".equals(tool) || "edit_file".equals(tool) || "apply_patch".equals(tool)
                || "write".equals(tool) || "edit".equals(tool) || "patch".equals(tool);
    }

    private boolean isVerificationTool(String tool) {
        return "run_tests".equals(tool) || "execute_code".equals(tool) || "shell".equals(tool)
                || "bash".equals(tool) || "run_command".equals(tool) || "diagnostics".equals(tool);
    }

    private String stageGuidance(String stage) {
        return switch (stage == null ? "intake" : stage) {
            case "explore" -> "Focus: locate relevant files, APIs, commands, and constraints before editing.";
            case "design" -> "Focus: choose the smallest coherent implementation and identify verification commands.";
            case "implement" -> "Focus: edit only scoped files, preserve existing behavior, and keep changes reviewable.";
            case "verify" -> "Focus: run targeted checks, inspect failures, and summarize remaining risk.";
            case "repair" -> "Focus: use the latest failure output to fix root cause before retrying verification.";
            case "final" -> "Focus: report actual changes, verification, and residual risk.";
            default -> "Focus: clarify task, create a concrete plan, then explore before editing.";
        };
    }

    private String buildDiagnosticsContext(StudentProject project,
                                           String activePath,
                                           Collection<String> touchedFiles,
                                           int maxFiles) {
        if (project == null || project.getWorkspacePath() == null) {
            return "- no workspace path available";
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (activePath != null && !activePath.isBlank()) {
            paths.add(activePath);
        }
        if (touchedFiles != null) {
            List<String> touched = new ArrayList<>(touchedFiles);
            for (int i = touched.size() - 1; i >= 0 && paths.size() < maxFiles; i--) {
                String path = touched.get(i);
                if (path != null && !path.isBlank()) {
                    paths.add(path);
                }
            }
        }
        if (paths.isEmpty()) {
            return "- no active or recently touched files";
        }
        Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        StringBuilder out = new StringBuilder();
        int analyzed = 0;
        int totalIssues = 0;
        for (String relative : paths) {
            if (analyzed >= maxFiles) break;
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !isDiagnosable(file)) {
                continue;
            }
            analyzed++;
            try {
                LspSessionManager.LspDiagnosticsResult diagnostics = lspSessionManager.diagnostics(root, file);
                if (!diagnostics.available()) {
                    totalIssues++;
                    out.append("- ").append(toWorkspacePath(root, file)).append(": LSP unavailable - ")
                            .append(diagnostics.message()).append('\n');
                    continue;
                }
                if (diagnostics.diagnostics().isEmpty()) {
                    out.append("- ").append(toWorkspacePath(root, file)).append(": real LSP clean\n");
                    continue;
                }
                totalIssues += diagnostics.diagnostics().size();
                out.append("- ").append(toWorkspacePath(root, file)).append(": ")
                        .append(diagnostics.diagnostics().size()).append(" real LSP issue(s)\n");
                diagnostics.diagnostics().stream().limit(8).forEach(d -> out.append("  - [")
                        .append(d.getSeverity()).append("] line ")
                        .append(d.getRange() == null ? 1 : d.getRange().getStart().getLine() + 1)
                        .append(": ").append(d.getMessage()).append('\n'));
            } catch (Exception e) {
                out.append("- ").append(relative).append(": diagnostics unavailable (")
                        .append(e.getMessage()).append(")\n");
            }
        }
        if (analyzed == 0) {
            return "- no diagnosable active or recently touched files";
        }
        out.append("- analyzed_files: ").append(analyzed).append(", total_issues: ").append(totalIssues).append('\n');
        return out.toString().trim();
    }

    private boolean isDiagnosable(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return Set.of(".java", ".js", ".jsx", ".ts", ".tsx", ".vue", ".py", ".json", ".css", ".scss").stream()
                .anyMatch(name::endsWith);
    }

    private String toWorkspacePath(Path root, Path file) {
        try {
            return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    private String limit(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "\n...context trimmed by orchestrator...";
    }

    private String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(8, bytes.length); i++) {
                out.append(String.format("%02x", bytes[i]));
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public record ContextBundle(String content, Map<String, Object> stats) {}
}
