package com.labex.labexagent.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AgentWorkspaceMemoryService {
    private static final Gson GSON = new Gson();
    private static final int MAX_FILES = 80;
    private static final int MAX_EVENTS = 40;

    public WorkspaceMemory readMemory(StudentProject project) {
        Path path = memoryPath(project);
        if (!Files.isRegularFile(path)) {
            return new WorkspaceMemory();
        }
        try {
            WorkspaceMemory memory = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), WorkspaceMemory.class);
            return memory == null ? new WorkspaceMemory() : memory.normalize();
        } catch (Exception ignored) {
            return new WorkspaceMemory();
        }
    }

    public String buildMemoryContext(StudentProject project, String userMessage, String activePath) {
        WorkspaceMemory memory = readMemory(project);
        StringBuilder builder = new StringBuilder();
        builder.append("Workspace memory (`.labex/agent-memory.json`):\n");
        if (!memory.touchedFiles.isEmpty()) {
            builder.append("- touched_files: ").append(String.join(", ", tail(memory.touchedFiles, 12))).append('\n');
        }
        if (!memory.verifications.isEmpty()) {
            builder.append("- recent_verifications:\n");
            for (MemoryEvent event : tail(memory.verifications, 6)) {
                builder.append("  - ").append(event.summary).append('\n');
            }
        }
        if (!memory.decisions.isEmpty()) {
            builder.append("- durable_decisions:\n");
            for (MemoryEvent event : tail(memory.decisions, 8)) {
                builder.append("  - ").append(event.summary).append('\n');
            }
        }
        if (!memory.failures.isEmpty()) {
            builder.append("- recent_failures:\n");
            for (MemoryEvent event : tail(memory.failures, 6)) {
                builder.append("  - ").append(event.summary).append('\n');
            }
        }
        List<FileMemory> relevant = relevantFiles(memory, userMessage, activePath);
        if (!relevant.isEmpty()) {
            builder.append("- cached_file_facts:\n");
            for (FileMemory file : relevant) {
                builder.append("  - ").append(file.path)
                        .append(" hash=").append(shortHash(file.hash))
                        .append(" :: ").append(limit(file.summary, 260)).append('\n');
            }
        }
        if (builder.toString().endsWith(":\n")) {
            builder.append("- no durable workspace memory yet\n");
        }
        return limit(builder.toString(), 6000);
    }

    public void recordToolResult(AgentContext context, String toolName, JsonObject args, ToolResult result) {
        if (context == null || context.getProject() == null || toolName == null || result == null) {
            return;
        }
        try {
            WorkspaceMemory memory = readMemory(context.getProject());
            String safeTool = toolName.trim().toLowerCase(Locale.ROOT);
            if ("read_file".equals(safeTool) && result.isSuccess()) {
                recordRead(context, args, memory);
            } else if (isWriteTool(safeTool) && result.isSuccess()) {
                recordWrite(context, args, memory, safeTool);
            } else if (isVerificationTool(safeTool)) {
                recordVerification(args, result, memory, safeTool);
            }
            if (!result.isSuccess()) {
                recordFailure(args, result, memory, safeTool);
            }
            memory.updatedAt = LocalDateTime.now().toString();
            trim(memory);
            writeMemory(context.getProject(), memory);
        } catch (Exception ignored) {
            // Memory must never break the agent loop.
        }
    }

    public void recordDecision(AgentContext context, String title, String content) {
        if (context == null || context.getProject() == null) return;
        try {
            WorkspaceMemory memory = readMemory(context.getProject());
            MemoryEvent event = new MemoryEvent();
            event.time = LocalDateTime.now().toString();
            event.tool = "context_note";
            event.summary = limit(normalize(title), 120) + " :: " + limit(normalize(content), 420);
            memory.decisions.add(event);
            memory.updatedAt = LocalDateTime.now().toString();
            trim(memory);
            writeMemory(context.getProject(), memory);
        } catch (Exception ignored) {
            // Ignore memory failures.
        }
    }

    private void recordRead(AgentContext context, JsonObject args, WorkspaceMemory memory) throws Exception {
        String path = normalizePathArg(args);
        if (path.isBlank()) return;
        Path file = ToolSupport.resolve(context, path);
        if (!Files.isRegularFile(file)) return;
        String content = Files.readString(file, StandardCharsets.UTF_8);
        FileMemory entry = new FileMemory();
        entry.path = path;
        entry.hash = sha256(content);
        entry.size = content.length();
        entry.summary = summarizeFile(path, content);
        entry.lastReadAt = LocalDateTime.now().toString();
        memory.files.put(path, entry);
    }

    private void recordWrite(AgentContext context, JsonObject args, WorkspaceMemory memory, String tool) throws Exception {
        String path = normalizePathArg(args);
        if (path.isBlank() && args.has("file_path")) {
            path = args.get("file_path").getAsString();
        }
        path = ToolSupport.normalizeRelativePath(path);
        if (path.isBlank()) return;
        memory.touchedFiles.remove(path);
        memory.touchedFiles.add(path);
        Path file = ToolSupport.resolve(context, path);
        if (Files.isRegularFile(file)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            FileMemory entry = new FileMemory();
            entry.path = path;
            entry.hash = sha256(content);
            entry.size = content.length();
            entry.summary = "Modified by " + tool + ". " + summarizeFile(path, content);
            entry.lastReadAt = LocalDateTime.now().toString();
            memory.files.put(path, entry);
        }
    }

    private void recordVerification(JsonObject args, ToolResult result, WorkspaceMemory memory, String tool) {
        String command = commandArg(args);
        MemoryEvent event = new MemoryEvent();
        event.time = LocalDateTime.now().toString();
        event.tool = tool;
        event.summary = (result.isSuccess() ? "PASS " : "FAIL ") + tool + (command.isBlank() ? "" : " `" + limit(command, 120) + "`")
                + " :: " + limit(normalize(result.getContent()), 220);
        memory.verifications.add(event);
    }

    private void recordFailure(JsonObject args, ToolResult result, WorkspaceMemory memory, String tool) {
        MemoryEvent event = new MemoryEvent();
        event.time = LocalDateTime.now().toString();
        event.tool = tool;
        String target = commandArg(args);
        if (target.isBlank()) target = normalizePathArg(args);
        event.summary = tool + (target.isBlank() ? "" : " `" + limit(target, 120) + "`")
                + " failed :: " + limit(normalize(result.getContent()), 260);
        memory.failures.add(event);
    }

    private List<FileMemory> relevantFiles(WorkspaceMemory memory, String userMessage, String activePath) {
        String query = (userMessage == null ? "" : userMessage).toLowerCase(Locale.ROOT);
        Set<String> terms = Arrays.stream(query.split("\\W+"))
                .filter(s -> s.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<FileMemory> all = new ArrayList<>(memory.files.values());
        all.sort((a, b) -> Integer.compare(score(b, terms, activePath), score(a, terms, activePath)));
        return all.stream().filter(f -> score(f, terms, activePath) > 0).limit(10).toList();
    }

    private int score(FileMemory file, Set<String> terms, String activePath) {
        int score = 0;
        String hay = (file.path + " " + file.summary).toLowerCase(Locale.ROOT);
        if (activePath != null && !activePath.isBlank() && activePath.equals(file.path)) score += 20;
        for (String term : terms) {
            if (hay.contains(term)) score += 3;
        }
        if (score == 0 && file.lastReadAt != null) score = 1;
        return score;
    }

    private String summarizeFile(String path, String content) {
        StringBuilder summary = new StringBuilder();
        summary.append(path).append(" lines=").append(content.split("\\R", -1).length).append(". ");
        int added = 0;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("(@(Get|Post|Put|Delete|Request)Mapping.*|class .*|interface .*|enum .*|public .*\\(|private .*\\(|protected .*\\(|function .*|const .*=>.*|export default.*|def .*)")) {
                if (added++ > 0) summary.append(" | ");
                summary.append(limit(trimmed, 140));
            }
            if (added >= 6) break;
        }
        if (added == 0) {
            summary.append(limit(normalize(content), 360));
        }
        return summary.toString();
    }

    private void trim(WorkspaceMemory memory) {
        while (memory.files.size() > MAX_FILES) {
            String first = memory.files.keySet().iterator().next();
            memory.files.remove(first);
        }
        memory.touchedFiles = tail(memory.touchedFiles, 40);
        memory.verifications = tail(memory.verifications, MAX_EVENTS);
        memory.failures = tail(memory.failures, MAX_EVENTS);
        memory.decisions = tail(memory.decisions, MAX_EVENTS);
    }

    private void writeMemory(StudentProject project, WorkspaceMemory memory) throws Exception {
        Path path = memoryPath(project);
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(memory), StandardCharsets.UTF_8);
    }

    private Path memoryPath(StudentProject project) {
        return Path.of(project.getWorkspacePath()).toAbsolutePath().normalize()
                .resolve(".labex").resolve("agent-memory.json");
    }

    private boolean isWriteTool(String tool) {
        return Set.of("write_file", "write", "edit_file", "edit", "apply_patch", "patch").contains(tool);
    }

    private boolean isVerificationTool(String tool) {
        return Set.of("run_tests", "execute_code", "shell", "bash", "run_command").contains(tool);
    }

    private String normalizePathArg(JsonObject args) {
        if (args == null) return "";
        for (String key : List.of("file_path", "path", "filePath")) {
            if (args.has(key) && !args.get(key).isJsonNull()) {
                return ToolSupport.normalizeRelativePath(args.get(key).getAsString());
            }
        }
        return "";
    }

    private String commandArg(JsonObject args) {
        if (args == null) return "";
        for (String key : List.of("command", "cmd", "code")) {
            if (args.has(key) && !args.get(key).isJsonNull()) {
                return args.get(key).getAsString();
            }
        }
        return "";
    }

    private static <T> List<T> tail(List<T> list, int limit) {
        if (list == null || list.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(list.subList(Math.max(0, list.size() - limit), list.size()));
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String limit(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String shortHash(String hash) {
        return hash == null || hash.length() <= 10 ? String.valueOf(hash) : hash.substring(0, 10);
    }

    private String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format("%02x", b));
        return out.toString();
    }

    public static class WorkspaceMemory {
        public String updatedAt = "";
        public Map<String, FileMemory> files = new LinkedHashMap<>();
        public List<String> touchedFiles = new ArrayList<>();
        public List<MemoryEvent> failures = new ArrayList<>();
        public List<MemoryEvent> verifications = new ArrayList<>();
        public List<MemoryEvent> decisions = new ArrayList<>();

        WorkspaceMemory normalize() {
            if (files == null) files = new LinkedHashMap<>();
            if (touchedFiles == null) touchedFiles = new ArrayList<>();
            if (failures == null) failures = new ArrayList<>();
            if (verifications == null) verifications = new ArrayList<>();
            if (decisions == null) decisions = new ArrayList<>();
            return this;
        }
    }

    public static class FileMemory {
        public String path;
        public String hash;
        public int size;
        public String summary;
        public String lastReadAt;
    }

    public static class MemoryEvent {
        public String time;
        public String tool;
        public String summary;
    }
}
