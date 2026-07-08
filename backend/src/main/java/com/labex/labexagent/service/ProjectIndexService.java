package com.labex.labexagent.service;

import com.labex.entity.StudentProject;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Service
public class ProjectIndexService {
    private static final int MAX_FILES = 900;
    private static final int MAX_FILE_SIZE = 300000;
    private static final Set<String> IGNORE_DIRS = Set.of(".git", "node_modules", "dist", "build", "target", "__pycache__", ".idea", ".vscode", ".gradle", ".mvn");
    private static final Set<String> TEXT_EXTS = Set.of("java", "py", "js", "jsx", "ts", "tsx", "vue", "html", "css", "scss", "less", "json", "xml", "yml", "yaml", "md", "txt", "properties", "ini", "conf", "cfg", "toml", "sql", "sh", "bat", "cmd", "ps1", "gradle", "go", "rs", "php", "rb", "c", "h", "cpp", "hpp");

    public String buildProjectDigest(StudentProject project, String query) {
        List<IndexedFile> files = scan(project);
        Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        StringBuilder digest = new StringBuilder();
        digest.append("Project index (Labex project memory):\n");
        digest.append("\n## Entrypoints\n").append(listEntrypoints(files));
        digest.append("\n## Scripts And Commands\n").append(detectScriptsAndCommands(root, files));
        digest.append("\n## Routes And APIs\n").append(detectRoutesAndApis(files));
        digest.append("\n## Recent Modified Files\n").append(listRecentFiles(root, files));
        digest.append("\n## File Digest\n");
        int count = 0;
        for (IndexedFile file : files) {
            if (count >= 120) break;
            digest.append("- ").append(file.path()).append(" (~").append(file.size()).append(" bytes)");
            if (file.hints() != null && !file.hints().isBlank()) {
                digest.append(" :: ").append(file.hints());
            }
            digest.append('\n');
            count++;
        }
        List<SearchHit> hits = searchFiles(project, query, 8);
        if (!hits.isEmpty()) {
            digest.append("\nPossible related snippets:\n");
            for (SearchHit hit : hits) {
                digest.append("\n### ").append(hit.path()).append('\n').append(hit.preview()).append('\n');
            }
        }
        return limit(digest.toString(), 24000);
    }

    public String buildAdaptiveProjectContext(StudentProject project, String query, Collection<String> priorityPaths) {
        List<IndexedFile> files = scan(project);
        Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        StringBuilder context = new StringBuilder();
        context.append("Adaptive project context:\n");
        context.append("\n## Entrypoints\n").append(listEntrypoints(files));
        context.append("\n## Scripts And Commands\n").append(detectScriptsAndCommands(root, files));
        context.append("\n## Routes And APIs\n").append(detectRoutesAndApis(files));
        context.append("\n## Changed Or Remembered Files\n").append(listPriorityFiles(files, priorityPaths));
        context.append("\n## Symbol Map\n").append(symbolMap(files, 90));

        List<SearchHit> hits = searchFiles(project, query, 12);
        if (!hits.isEmpty()) {
            context.append("\n## Task Related Hits\n");
            for (SearchHit hit : hits) {
                context.append("\n### ").append(hit.path()).append(" score=").append(hit.score()).append('\n')
                        .append(limit(hit.preview(), 1800)).append('\n');
            }
        }
        context.append("\n## Indexing Rules\n")
                .append("- Treat this as a map, not source of truth. Read exact files before editing.\n")
                .append("- Prefer touched/relevant files first, then entrypoints, then symbol hits.\n");
        return limit(context.toString(), 26000);
    }

    private String listEntrypoints(List<IndexedFile> files) {
        List<String> names = List.of("package.json", "pom.xml", "build.gradle", "vite.config.js", "vite.config.ts", "run.py", "app.py", "main.py", "index.html", "src/main.js", "src/main.jsx", "src/main.ts", "src/main.tsx", "src/App.vue", "src/App.jsx", "src/App.tsx");
        StringBuilder builder = new StringBuilder();
        for (IndexedFile file : files) {
            if (names.contains(file.path()) || file.path().endsWith("Application.java")) {
                builder.append("- ").append(file.path()).append('\n');
            }
        }
        return builder.isEmpty() ? "- No entry files detected\n" : builder.toString();
    }

    private String detectScriptsAndCommands(Path root, List<IndexedFile> files) {
        StringBuilder builder = new StringBuilder();
        Path packageJson = root.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            String content = read(packageJson);
            builder.append("- Node package detected: package.json\n");
            for (String script : List.of("dev", "start", "build", "test", "preview")) {
                if (content.contains("\"" + script + "\"")) {
                    builder.append("  - npm run ").append(script).append('\n');
                }
            }
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            builder.append("- Maven project: mvn spring-boot:run, mvn test, mvn clean package\n");
        }
        if (Files.isRegularFile(root.resolve("requirements.txt")) || Files.isRegularFile(root.resolve("pyproject.toml"))) {
            builder.append("- Python project: pip install -r requirements.txt, python run.py, python -m pytest\n");
        }
        return builder.isEmpty() ? "- No scripts detected\n" : builder.toString();
    }

    private String detectRoutesAndApis(List<IndexedFile> files) {
        StringBuilder builder = new StringBuilder();
        for (IndexedFile file : files) {
            String path = file.path();
            if (path.contains("Controller") || path.contains("Route") || path.contains("router")) {
                builder.append("- ").append(path).append('\n');
            }
        }
        return builder.isEmpty() ? "- No routes detected\n" : builder.toString();
    }

    private String listRecentFiles(Path root, List<IndexedFile> files) {
        files.sort(Comparator.comparingLong(f -> -lastModified(f.absolutePath())));
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (IndexedFile file : files) {
            if (count >= 10) break;
            builder.append("- ").append(file.path()).append('\n');
            count++;
        }
        return builder.isEmpty() ? "- No files\n" : builder.toString();
    }

    private String listPriorityFiles(List<IndexedFile> files, Collection<String> priorityPaths) {
        if (priorityPaths == null || priorityPaths.isEmpty()) {
            return "- No remembered file changes yet\n";
        }
        Set<String> existing = files.stream().map(IndexedFile::path).collect(java.util.stream.Collectors.toSet());
        StringBuilder builder = new StringBuilder();
        for (String path : priorityPaths) {
            if (path == null || path.isBlank()) continue;
            builder.append("- ").append(path);
            if (!existing.contains(path)) builder.append(" (not in current index)");
            builder.append('\n');
        }
        return builder.isEmpty() ? "- No remembered file changes yet\n" : builder.toString();
    }

    private String symbolMap(List<IndexedFile> files, int limit) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (IndexedFile file : files) {
            String hints = file.hints();
            if (hints == null || hints.isBlank()) continue;
            builder.append("- ").append(file.path()).append(" :: ").append(hints).append('\n');
            if (++count >= limit) break;
        }
        return builder.isEmpty() ? "- No symbols detected\n" : builder.toString();
    }

    private long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception e) { return 0L; }
    }

    public List<SearchHit> searchFiles(StudentProject project, String query, int limit) {
        List<SearchHit> hits = new ArrayList<>();
        if (query == null || query.isBlank()) return hits;
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        List<IndexedFile> files = scan(project);
        for (IndexedFile file : files) {
            if (hits.size() >= limit) break;
            String content = read(file.absolutePath());
            if (content.isBlank()) continue;
            String preview = preview(content, Arrays.asList(terms));
            int score = 0;
            for (String term : terms) {
                if (file.path().toLowerCase(Locale.ROOT).contains(term)) score += 5;
                if (content.toLowerCase(Locale.ROOT).contains(term)) score += 1;
            }
            if (score > 0) {
                hits.add(new SearchHit(file.path(), preview, score));
            }
        }
        hits.sort(Comparator.comparingInt(SearchHit::score).reversed());
        return hits.subList(0, Math.min(hits.size(), limit));
    }

    private List<IndexedFile> scan(StudentProject project) {
        Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        List<IndexedFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.filter(p -> Files.isRegularFile(p)).limit(2700L).toList();
            for (Path path : paths) {
                if (files.size() >= MAX_FILES) break;
                if (isIgnored(root, path) || !isTextFile(path)) continue;
                try {
                    if (Files.size(path) > MAX_FILE_SIZE) continue;
                } catch (Exception e) { continue; }
                String relative = root.relativize(path).toString().replace('\\', '/');
                files.add(new IndexedFile(relative, path, path.toFile().length(), hints(path)));
            }
        } catch (Exception e) { /* ignore */ }
        files.sort(Comparator.comparing(IndexedFile::path));
        return files;
    }

    private boolean isIgnored(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (IGNORE_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    private boolean isTextFile(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return Set.of("Dockerfile", "Makefile", "README", "LICENSE").contains(name);
        return TEXT_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String hints(Path path) {
        String content = limit(read(path), 6000);
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("(class|def|function|const|let|var|public|private|protected|export|interface|enum|record|@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping).*")
                    || trimmed.matches(".*(setup\\(|defineProps\\(|defineEmits\\(|router\\.|app\\.|Controller|Service|Mapper).*")) {
                lines.add(trimmed);
            }
            if (lines.size() >= 4) break;
        }
        return String.join(" | ", lines);
    }

    private String preview(String content, List<String> terms) {
        String[] lines = content.split("\\R");
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (int i = 0; i < lines.length && added < 12; i++) {
            boolean match = (terms == null || terms.isEmpty());
            if (!match) {
                String lower = lines[i].toLowerCase(Locale.ROOT);
                for (String term : terms) {
                    if (term.length() >= 2 && lower.contains(term)) {
                        match = true;
                        break;
                    }
                }
            }
            if (!match && content.length() >= 4000) continue;
            builder.append(i + 1).append(": ").append(lines[i]).append('\n');
            added++;
        }
        if (builder.isEmpty()) return limit(content, 2200);
        return limit(builder.toString(), 3000);
    }

    private String read(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            for (byte b : bytes) { if (b == 0) return ""; }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private String limit(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "\n...truncated...";
    }

    public static class IndexedFile {
        private final String relativePath;
        private final Path absolutePath;
        private final long size;
        private final String hints;
        public IndexedFile(String relativePath, Path absolutePath, long size, String hints) {
            this.relativePath = relativePath; this.absolutePath = absolutePath; this.size = size; this.hints = hints;
        }
        public String path() { return relativePath; }
        public long size() { return size; }
        public String hints() { return hints; }
        public Path absolutePath() { return absolutePath; }
    }

    public static class SearchHit {
        private final String path;
        private final String preview;
        private final int score;
        public SearchHit(String path, String preview, int score) {
            this.path = path; this.preview = preview; this.score = score;
        }
        public String path() { return path; }
        public String preview() { return preview; }
        public int score() { return score; }
    }
}
