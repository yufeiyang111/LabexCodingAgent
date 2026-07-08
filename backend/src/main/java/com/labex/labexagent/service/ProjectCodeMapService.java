package com.labex.labexagent.service;

import com.labex.entity.StudentProject;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ProjectCodeMapService {
    private static final int MAX_FILES = 700;
    private static final int MAX_BYTES = 260_000;
    private static final Set<String> IGNORE_DIRS = Set.of(
            ".git", "node_modules", "dist", "build", "target", "__pycache__", ".idea", ".vscode", ".gradle", ".mvn");
    private static final Set<String> CODE_EXTS = Set.of(
            "java", "js", "jsx", "ts", "tsx", "vue", "py", "go", "rs", "php", "rb");

    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern JAVA_TYPE = Pattern.compile("^\\s*(?:public|private|protected)?\\s*(?:abstract|final|static)?\\s*(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile("^\\s*(?:public|private|protected)?\\s*(?:static|final|abstract|synchronized|default)?\\s*[\\w<>,.?\\s\\[\\]]+\\s+(\\w+)\\s*\\(");
    private static final Pattern JAVA_ROUTE = Pattern.compile("@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\s*(?:\\(\\s*)?(?:value\\s*=\\s*)?\"([^\"]*)\"");

    private static final Pattern JS_IMPORT = Pattern.compile("^\\s*import\\s+(?:.+?\\s+from\\s+)?['\"]([^'\"]+)['\"]");
    private static final Pattern JS_EXPORT = Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?(?:function\\s+(\\w+)|const\\s+(\\w+)\\s*=|class\\s+(\\w+)|interface\\s+(\\w+))");
    private static final Pattern VUE_ROUTE = Pattern.compile("path\\s*:\\s*['\"]([^'\"]+)['\"]");

    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))");
    private static final Pattern PY_SYMBOL = Pattern.compile("^\\s*(class|def|async\\s+def)\\s+(\\w+)\\s*\\(");

    public String buildRepoMap(StudentProject project, String query, List<String> priorityPaths, int maxFiles) {
        if (project == null || project.getWorkspacePath() == null) {
            return "Repo map unavailable: workspace path missing.";
        }
        List<MappedFile> files = scan(project);
        if (files.isEmpty()) {
            return "Repo map: no code files indexed.";
        }
        Set<String> terms = queryTerms(query);
        Set<String> priority = normalizePriority(priorityPaths);
        files.sort(Comparator.comparingInt((MappedFile f) -> score(f, terms, priority)).reversed()
                .thenComparing(MappedFile::path));

        StringBuilder out = new StringBuilder();
        out.append("Repo map (structured symbols, routes and imports):\n");
        out.append("- indexed_files: ").append(files.size()).append('\n');
        out.append("- ranking: query terms + remembered/touched files + symbol/import matches\n\n");

        int emitted = 0;
        for (MappedFile file : files) {
            int score = score(file, terms, priority);
            if (emitted >= maxFiles) break;
            if (emitted > 0 && score <= 0 && !priority.contains(file.path())) break;
            emitted++;
            out.append("## ").append(file.path()).append(" score=").append(score)
                    .append(" lines=").append(file.lines()).append('\n');
            if (!file.imports().isEmpty()) {
                out.append("- imports: ").append(String.join(", ", limitList(file.imports(), 8))).append('\n');
            }
            if (!file.routes().isEmpty()) {
                out.append("- routes: ").append(String.join(", ", limitList(file.routes(), 8))).append('\n');
            }
            if (file.symbols().isEmpty()) {
                out.append("- symbols: none detected\n\n");
                continue;
            }
            out.append("- symbols:\n");
            for (Symbol symbol : file.symbols().stream().limit(14).toList()) {
                out.append("  - L").append(symbol.line()).append(" ")
                        .append(symbol.kind()).append(" ").append(symbol.name()).append('\n');
            }
            out.append('\n');
        }
        if (emitted == 0) {
            out.append("No query-specific files; use project_overview or grep for discovery.\n");
        }
        out.append("Use repo_map as navigation only. Read exact files before editing.\n");
        return limit(out.toString(), 24_000);
    }

    private List<MappedFile> scan(StudentProject project) {
        Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        List<MappedFile> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.filter(Files::isRegularFile).limit(3000).toList();
            for (Path path : paths) {
                if (result.size() >= MAX_FILES) break;
                if (isIgnored(root, path) || !isCodeFile(path)) continue;
                try {
                    if (Files.size(path) > MAX_BYTES) continue;
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    if (content.indexOf('\0') >= 0) continue;
                    result.add(mapFile(root, path, content));
                } catch (Exception ignored) {
                    // Indexing must never break the agent loop.
                }
            }
        } catch (Exception ignored) {
            // Return whatever was indexed.
        }
        return result;
    }

    private MappedFile mapFile(Path root, Path file, String content) {
        String path = root.relativize(file).toString().replace('\\', '/');
        String[] lines = content.split("\\R", -1);
        List<Symbol> symbols = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        List<String> routes = new ArrayList<>();
        String ext = extension(file);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) {
                continue;
            }
            collectImports(ext, line, imports);
            collectRoutes(ext, line, routes);
            Symbol symbol = extractSymbol(ext, line, i + 1);
            if (symbol != null) symbols.add(symbol);
        }
        return new MappedFile(path, lines.length, symbols, unique(imports), unique(routes));
    }

    private void collectImports(String ext, String line, List<String> imports) {
        Matcher m;
        if ("java".equals(ext) && (m = JAVA_IMPORT.matcher(line)).find()) {
            imports.add(m.group(1));
        } else if (Set.of("js", "jsx", "ts", "tsx", "vue").contains(ext) && (m = JS_IMPORT.matcher(line)).find()) {
            imports.add(m.group(1));
        } else if ("py".equals(ext) && (m = PY_IMPORT.matcher(line)).find()) {
            imports.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
    }

    private void collectRoutes(String ext, String line, List<String> routes) {
        Matcher m;
        if ("java".equals(ext) && (m = JAVA_ROUTE.matcher(line)).find()) {
            routes.add(m.group(1));
        } else if (Set.of("js", "jsx", "ts", "tsx", "vue").contains(ext) && (m = VUE_ROUTE.matcher(line)).find()) {
            routes.add(m.group(1));
        }
    }

    private Symbol extractSymbol(String ext, String line, int lineNo) {
        Matcher m;
        if ("java".equals(ext)) {
            m = JAVA_TYPE.matcher(line);
            if (m.find()) return new Symbol(m.group(1), m.group(2), lineNo);
            m = JAVA_METHOD.matcher(line);
            if (m.find() && !Set.of("if", "for", "while", "switch", "catch").contains(m.group(1))) {
                return new Symbol("method", m.group(1), lineNo);
            }
        } else if (Set.of("js", "jsx", "ts", "tsx", "vue").contains(ext)) {
            m = JS_EXPORT.matcher(line);
            if (m.find()) {
                String name = firstNonBlank(m.group(1), m.group(2), m.group(3), m.group(4));
                String kind = line.contains("class ") ? "class" : line.contains("interface ") ? "interface" : "function";
                return name == null ? null : new Symbol(kind, name, lineNo);
            }
        } else if ("py".equals(ext)) {
            m = PY_SYMBOL.matcher(line);
            if (m.find()) return new Symbol(m.group(1).replace("async ", ""), m.group(2), lineNo);
        }
        return null;
    }

    private int score(MappedFile file, Set<String> terms, Set<String> priorityPaths) {
        int score = priorityPaths.contains(file.path()) ? 50 : 0;
        String hay = (file.path() + " " + file.imports() + " " + file.routes() + " " + file.symbols()).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (file.path().toLowerCase(Locale.ROOT).contains(term)) score += 8;
            if (hay.contains(term)) score += 4;
        }
        if (!file.routes().isEmpty()) score += 2;
        if (!file.symbols().isEmpty()) score += 1;
        return score;
    }

    private Set<String> queryTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (query == null) return terms;
        for (String part : query.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_./-]+")) {
            if (part.length() >= 2) terms.add(part);
        }
        return terms;
    }

    private Set<String> normalizePriority(List<String> paths) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (paths == null) return out;
        for (String path : paths) {
            if (path != null && !path.isBlank()) out.add(path.trim().replace('\\', '/'));
        }
        return out;
    }

    private boolean isIgnored(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (IGNORE_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private boolean isCodeFile(Path path) {
        return CODE_EXTS.contains(extension(path));
    }

    private String extension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private List<String> unique(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private List<String> limitList(List<String> values, int max) {
        if (values.size() <= max) return values;
        List<String> out = new ArrayList<>(values.subList(0, max));
        out.add("+" + (values.size() - max) + " more");
        return out;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String limit(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "\n...repo map truncated...";
    }

    public record MappedFile(String path, int lines, List<Symbol> symbols, List<String> imports, List<String> routes) {}
    public record Symbol(String kind, String name, int line) {}
}
