package com.labex.labexagent.service;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自动发现并加载项目工作区内的指令与规则文件（对标 OpenCode instruction.ts，支持多层子项目/上传目录探测）。
 * 优先级：
 * 1. LabexAgent.md
 * 2. AGENTS.md
 * 3. CLAUDE.md
 * 4. .labex/rules/*.md
 */
@Service
public class WorkspaceInstructionService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceInstructionService.class);
    private static final int MAX_INSTRUCTION_CHARS = 16_000;
    private static final int MAX_SEARCH_DEPTH = 3;
    private static final List<String> PRIMARY_INSTRUCTION_FILES = List.of(
            "LabexAgent.md",
            "AGENTS.md",
            "CLAUDE.md"
    );
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", ".idea", "dist", "build", ".gradle", "vendor", "__pycache__", ".venv"
    );

    public String loadInstructions(StudentProject project) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            return "";
        }
        try {
            Path root = ProjectWorkspace.paths(project).workspaceRoot();
            return loadInstructionsFromPath(root);
        } catch (Exception e) {
            log.debug("No valid workspace path for project instructions: {}", e.getMessage());
            return "";
        }
    }

    public String loadInstructionsFromPath(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();

        // 1. 扫描首要指令文件 (先查根目录，若根目录无则递归搜索子目录如 4425/LabexAgent.md)
        FoundInstruction foundPrimary = findPrimaryInstructionFile(root);
        if (foundPrimary != null) {
            appendFileContent(builder, foundPrimary.relativeName(), foundPrimary.file());
        }

        // 2. 扫描 .labex/rules 目录下的规则片段 (包括根目录及可能的主工程子目录)
        List<Path> rulesDirs = findRulesDirectories(root);
        for (Path rulesDir : rulesDirs) {
            if (builder.length() >= MAX_INSTRUCTION_CHARS) break;
            try (Stream<Path> stream = Files.list(rulesDir)) {
                List<Path> ruleFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .toList();
                for (Path ruleFile : ruleFiles) {
                    if (builder.length() >= MAX_INSTRUCTION_CHARS) break;
                    String rel = root.relativize(ruleFile).toString().replace('\\', '/');
                    appendFileContent(builder, rel, ruleFile);
                }
            } catch (IOException ignored) {
            }
        }

        if (builder.isEmpty()) {
            return "";
        }

        return "<project_instructions>\n"
                + "## Project instructions and repository rules\n"
                + "These instructions are defined by the project repository and MUST be followed during all development:\n\n"
                + limit(builder.toString().trim(), MAX_INSTRUCTION_CHARS)
                + "\n</project_instructions>";
    }

    private FoundInstruction findPrimaryInstructionFile(Path root) {
        // 第一阶段：先检查顶层根目录
        for (String filename : PRIMARY_INSTRUCTION_FILES) {
            Path file = root.resolve(filename);
            if (Files.isRegularFile(file)) {
                return new FoundInstruction(filename, file);
            }
        }

        // 第二阶段：递归搜索 1~3 层子目录 (例如上传的项目解压到 root/4425/LabexAgent.md)
        List<Path> candidateDirs = new ArrayList<>();
        collectCandidateSubdirectories(root, candidateDirs, 1);

        for (Path dir : candidateDirs) {
            for (String filename : PRIMARY_INSTRUCTION_FILES) {
                Path file = dir.resolve(filename);
                if (Files.isRegularFile(file)) {
                    String relativeName = root.relativize(file).toString().replace('\\', '/');
                    return new FoundInstruction(relativeName, file);
                }
            }
        }

        return null;
    }

    private void collectCandidateSubdirectories(Path currentDir, List<Path> result, int currentDepth) {
        if (currentDepth > MAX_SEARCH_DEPTH) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    String dirName = entry.getFileName().toString();
                    if (!IGNORED_DIRECTORIES.contains(dirName) && !dirName.startsWith(".")) {
                        result.add(entry);
                        collectCandidateSubdirectories(entry, result, currentDepth + 1);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private List<Path> findRulesDirectories(Path root) {
        List<Path> list = new ArrayList<>();
        Path rootRules = root.resolve(".labex").resolve("rules");
        if (Files.isDirectory(rootRules)) {
            list.add(rootRules);
        }

        List<Path> subDirs = new ArrayList<>();
        collectCandidateSubdirectories(root, subDirs, 1);
        for (Path dir : subDirs) {
            Path subRules = dir.resolve(".labex").resolve("rules");
            if (Files.isDirectory(subRules)) {
                list.add(subRules);
            }
        }
        return list;
    }

    private void appendFileContent(StringBuilder builder, String relativeName, Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!content.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append("\n\n---\n\n");
                }
                builder.append("### Instruction Source: ").append(relativeName).append("\n\n");
                builder.append(content);
            }
        } catch (Exception e) {
            log.debug("Failed to read instruction file {}: {}", relativeName, e.getMessage());
        }
    }

    private static String limit(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... [Instruction content truncated to stay within token budget]";
    }

    private record FoundInstruction(String relativeName, Path file) {}
}
