package com.labex.labexagent.service;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.lsp.LspSessionManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * In-process incremental repository metadata and lexical retrieval service. It still walks a
 * workspace to discover additions/removals, but unchanged entries reuse their retained content,
 * hash, terms and symbols instead of being read and parsed again.
 */
@Service
public class IncrementalContextService {
    private static final String LSP_SYMBOLS_ENABLED = "labex.agent.context.lsp-symbols.enabled";
    private static final String EMBEDDINGS_ENABLED = "labex.agent.context.embeddings.enabled";
    private static final int MAX_FILES = 900;
    private static final int MAX_FILE_SIZE = 300_000;
    private static final WorkspaceScanner.ScanBudget INDEX_SCAN_BUDGET =
            new WorkspaceScanner.ScanBudget(12_000, 3_000, 24, 3_000);
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "py", "js", "jsx", "ts", "tsx", "vue", "html", "css", "scss", "less", "json", "xml",
            "yml", "yaml", "md", "txt", "properties", "ini", "conf", "cfg", "toml", "sql", "sh", "bat",
            "cmd", "ps1", "gradle", "go", "rs", "php", "rb", "c", "h", "cpp", "hpp");
    private static final Pattern SYMBOL = Pattern.compile(
            "(?:class|interface|enum|record|def|function)\\s+([A-Za-z_$][\\w$]*)|"
                    + "(?:public|private|protected|static|async|export)?\\s*(?:[A-Za-z_$][\\w$<>?,\\[\\] ]*)\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern ROUTE = Pattern.compile(
            "@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\s*(?:\\(\\s*)?(?:value\\s*=\\s*)?\"([^\"]*)\"|path\\s*:\s*['\"]([^'\"]+)['\"]");

    private final LspSessionManager lspSessionManager;
    private final WorkspaceScanner workspaceScanner;
    private final IncrementalContextCache contextCache;

    public IncrementalContextService() {
        this(null, new WorkspaceScanner(), new IncrementalContextCache());
    }

    @Autowired
    public IncrementalContextService(@Lazy LspSessionManager lspSessionManager, WorkspaceScanner workspaceScanner,
                                     IncrementalContextCache contextCache) {
        this.lspSessionManager = lspSessionManager;
        this.workspaceScanner = workspaceScanner;
        this.contextCache = contextCache;
    }

    public boolean embeddingsEnabled() {
        return Boolean.parseBoolean(System.getProperty(EMBEDDINGS_ENABLED, "false"));
    }

    public boolean lspSymbolsEnabled() {
        return Boolean.parseBoolean(System.getProperty(LSP_SYMBOLS_ENABLED, "false"));
    }

    public IndexSnapshot index(StudentProject project) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            return new IndexSnapshot(List.of(), new IndexStats(0, 0, 0, 0, 0), IndexScanStatus.empty());
        }
        SecureWorkspacePath paths = new SecureWorkspacePath(Path.of(project.getWorkspacePath()));
        Path root = paths.workspaceRoot();
        String key = root.toAbsolutePath().normalize().toString();
        Map<String, ContextDocument> previousDocuments = contextCache.documents(key);
        Map<String, ContextDocument> next = new LinkedHashMap<>();
        Set<String> observedPaths = new LinkedHashSet<>();
        int[] counters = new int[3]; // new, changed, reused
        WorkspaceScanner.ScanResult scanResult;
        try {
            scanResult = workspaceScanner.scan(paths, INDEX_SCAN_BUDGET, null, (file, attributes) -> {
                if (next.size() >= MAX_FILES) return false;
                if (!isTextFile(file) || attributes.size() > MAX_FILE_SIZE) return true;
                String relative = root.relativize(file).toString().replace('\\', '/');
                observedPaths.add(relative);
                long modified = attributes.lastModifiedTime().toMillis();
                ContextDocument old = previousDocuments.get(relative);
                if (old != null && old.size() == attributes.size() && old.modifiedMillis() == modified) {
                    next.put(relative, old);
                    counters[2]++;
                    return true;
                }
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (content.indexOf('\0') >= 0) return true;
                    next.put(relative, document(root, relative, file, attributes.size(), modified, content));
                    if (old == null) counters[0]++; else counters[1]++;
                } catch (Exception ignored) {
                    // Unreadable file: leave it out of this snapshot rather than failing context retrieval.
                }
                return true;
            });
        } catch (Exception ignored) {
            scanResult = new WorkspaceScanner.ScanResult(0, 0, 0, 0,
                    WorkspaceScanner.StopReason.CANCELLED, 0);
        }
        int removed = 0;
        if (scanResult.stopReason() == WorkspaceScanner.StopReason.COMPLETED) {
            removed = (int) previousDocuments.keySet().stream().filter(path -> !observedPaths.contains(path)).count();
        } else {
            previousDocuments.forEach(next::putIfAbsent);
        }
        List<ContextDocument> documents = next.values().stream()
                .sorted(Comparator.comparing(ContextDocument::path))
                .toList();
        IndexStats stats = new IndexStats(documents.size(), counters[0], counters[1], counters[2], removed);
        contextCache.put(key, next);
        return new IndexSnapshot(documents, stats, IndexScanStatus.from(scanResult));
    }

    public RetrievalResult retrieve(StudentProject project, String query, List<String> priorityPaths, int limit) {
        return retrieve(index(project), query, priorityPaths, limit);
    }

    public RetrievalResult retrieve(IndexSnapshot snapshot, String query, List<String> priorityPaths, int limit) {
        long started = System.nanoTime();
        if (snapshot == null) {
            snapshot = new IndexSnapshot(List.of(), new IndexStats(0, 0, 0, 0, 0), IndexScanStatus.empty());
        }
        List<String> terms = terms(query);
        Set<String> priority = normalizePaths(priorityPaths);
        if (snapshot.documents().isEmpty()) {
            return new RetrievalResult(List.of(), snapshot.stats(), elapsedMillis(started));
        }
        Map<String, Integer> documentFrequency = documentFrequency(snapshot.documents(), terms);
        double averageLength = snapshot.documents().stream().mapToInt(document -> document.terms().size()).average().orElse(1.0);
        List<RetrievalHit> hits = new ArrayList<>();
        for (ContextDocument document : snapshot.documents()) {
            List<String> reasons = new ArrayList<>();
            double score = 0.0;
            if (priority.contains(document.path())) {
                score += 100.0;
                reasons.add("priority path");
            }
            for (String term : terms) {
                int frequency = document.terms().getOrDefault(term, 0);
                if (frequency == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (snapshot.documents().size() - df + 0.5) / (df + 0.5));
                double normalizedFrequency = frequency * 2.2 /
                        (frequency + 1.2 * (1.0 - 0.75 + 0.75 * document.terms().size() / averageLength));
                score += idf * normalizedFrequency;
                if (document.path().toLowerCase(Locale.ROOT).contains(term)) {
                    score += 8.0;
                    reasons.add("path:" + term);
                }
                if (document.symbols().stream().anyMatch(symbol -> symbol.toLowerCase(Locale.ROOT).contains(term))) {
                    score += 6.0;
                    reasons.add("symbol:" + term);
                } else {
                    reasons.add("lexical:" + term);
                }
            }
            if (embeddingsEnabled()) {
                double similarity = cosine(embed(String.join(" ", terms)), document.embedding());
                if (similarity > 0.0) {
                    score += similarity * 2.0;
                    reasons.add("embedding:" + String.format(Locale.ROOT, "%.2f", similarity));
                }
            }
            if (score > 0) {
                hits.add(new RetrievalHit(document.path(), preview(document.content(), terms), score,
                        unique(reasons), document.symbols()));
            }
        }
        hits.sort(Comparator.comparingDouble(RetrievalHit::score).reversed().thenComparing(RetrievalHit::path));
        return new RetrievalResult(hits.subList(0, Math.min(Math.max(0, limit), hits.size())), snapshot.stats(), elapsedMillis(started));
    }

    public BenchmarkReport benchmark(StudentProject project, List<BenchmarkCase> cases, int limit) {
        List<BenchmarkSample> samples = new ArrayList<>();
        if (cases == null) {
            return new BenchmarkReport(samples);
        }
        for (BenchmarkCase benchmarkCase : cases) {
            IncrementalContextService coldScanner = new IncrementalContextService();
            RetrievalResult cold = coldScanner.retrieve(project, benchmarkCase.query(), benchmarkCase.priorityPaths(), limit);
            RetrievalResult incremental = retrieve(project, benchmarkCase.query(), benchmarkCase.priorityPaths(), limit);
            List<String> coldPaths = cold.hits().stream().map(RetrievalHit::path).toList();
            List<String> incrementalPaths = incremental.hits().stream().map(RetrievalHit::path).toList();
            samples.add(new BenchmarkSample(benchmarkCase.name(), benchmarkCase.query(), cold.elapsedMillis(),
                    incremental.elapsedMillis(), coldPaths, incrementalPaths, overlap(coldPaths, incrementalPaths)));
        }
        return new BenchmarkReport(samples);
    }

    private double overlap(List<String> left, List<String> right) {
        if (left == null || left.isEmpty()) return right == null || right.isEmpty() ? 1.0 : 0.0;
        Set<String> common = new LinkedHashSet<>(left);
        common.retainAll(right == null ? Set.of() : right);
        return (double) common.size() / left.size();
    }

    private ContextDocument document(Path root, String path, Path absolutePath, long size, long modifiedMillis, String content) {
        return new ContextDocument(path, absolutePath, size, modifiedMillis, sha256(content), content,
                termFrequency(path + "\n" + content), symbols(root, absolutePath, content), embed(content));
    }

    private List<String> symbols(Path root, Path file, String content) {
        List<String> fallback = extractSymbols(content);
        if (!lspSymbolsEnabled() || lspSessionManager == null) {
            return fallback;
        }
        try {
            LspSessionManager.LspSymbolsResult result = lspSessionManager.documentSymbols(root, file);
            if (!result.available() || result.symbols().isEmpty()) {
                return fallback;
            }
            LinkedHashSet<String> symbols = new LinkedHashSet<>(fallback);
            for (String line : result.symbols()) {
                String normalized = line == null ? "" : line.replaceFirst("^.*?\\s", "").trim();
                if (!normalized.isBlank()) symbols.add(normalized);
            }
            return List.copyOf(symbols);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Map<String, Integer> documentFrequency(List<ContextDocument> documents, List<String> queryTerms) {
        Map<String, Integer> result = new HashMap<>();
        for (String term : queryTerms) {
            int count = 0;
            for (ContextDocument document : documents) {
                if (document.terms().containsKey(term)) count++;
            }
            result.put(term, count);
        }
        return result;
    }

    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> result = new HashMap<>();
        for (String term : terms(text)) result.merge(term, 1, Integer::sum);
        return result;
    }

    private List<String> terms(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null) return List.of();
        String normalized = value.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        for (String part : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}_./-]+")) {
            if (part.length() >= 2) result.add(stem(part));
        }
        return List.copyOf(result);
    }

    private String stem(String term) {
        if (term.endsWith("tion") && term.length() > 6) return term.substring(0, term.length() - 3);
        if (term.endsWith("ing") && term.length() > 5) return term.substring(0, term.length() - 3);
        if (term.endsWith("es") && term.length() > 4) return term.substring(0, term.length() - 2);
        if (term.endsWith("s") && term.length() > 3) return term.substring(0, term.length() - 1);
        return term;
    }

    private List<String> extractSymbols(String content) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        Matcher symbolMatcher = SYMBOL.matcher(content);
        while (symbolMatcher.find() && symbols.size() < 48) {
            String symbol = symbolMatcher.group(1) != null ? symbolMatcher.group(1) : symbolMatcher.group(2);
            if (symbol != null && !symbol.isBlank()) symbols.add(symbol);
        }
        Matcher routeMatcher = ROUTE.matcher(content);
        while (routeMatcher.find() && symbols.size() < 64) {
            String route = routeMatcher.group(1) != null ? routeMatcher.group(1) : routeMatcher.group(2);
            if (route != null && !route.isBlank()) symbols.add(route);
        }
        return List.copyOf(symbols);
    }

    private String preview(String content, List<String> queryTerms) {
        String[] lines = content.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            String lower = lines[index].toLowerCase(Locale.ROOT);
            if (queryTerms.stream().anyMatch(lower::contains)) {
                int start = Math.max(0, index - 2);
                int end = Math.min(lines.length, index + 3);
                StringBuilder preview = new StringBuilder();
                for (int line = start; line < end; line++) {
                    preview.append(line + 1).append(": ").append(lines[line]).append('\n');
                }
                return preview.toString();
            }
        }
        return content.length() <= 900 ? content : content.substring(0, 900) + "...";
    }

    private Set<String> normalizePaths(List<String> paths) {
        Set<String> result = new LinkedHashSet<>();
        if (paths == null) return result;
        for (String path : paths) {
            if (path != null && !path.isBlank()) result.add(path.trim().replace('\\', '/'));
        }
        return result;
    }

    private boolean isTextFile(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return Set.of("Dockerfile", "Makefile", "README", "LICENSE").contains(name);
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format("%02x", item));
            return value.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(content.hashCode());
        }
    }

    private double[] embed(String text) {
        double[] vector = new double[64];
        for (String term : terms(text)) {
            int bucket = Math.floorMod(term.hashCode(), vector.length);
            vector[bucket] += 1.0;
        }
        double norm = 0.0;
        for (double value : vector) norm += value * value;
        if (norm == 0.0) return vector;
        double scale = 1.0 / Math.sqrt(norm);
        for (int index = 0; index < vector.length; index++) vector[index] *= scale;
        return vector;
    }

    private double cosine(double[] left, double[] right) {
        double score = 0.0;
        for (int index = 0; index < Math.min(left.length, right.length); index++) score += left[index] * right[index];
        return score;
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    public record ContextDocument(String path, Path absolutePath, long size, long modifiedMillis, String hash,
                                  String content, Map<String, Integer> terms, List<String> symbols, double[] embedding) {
    }

    public record IndexStats(int indexedFiles, int newFiles, int changedFiles, int reusedFiles, int removedFiles) {
    }

    public record IndexSnapshot(List<ContextDocument> documents, IndexStats stats, IndexScanStatus scanStatus) {
    }

    public record IndexScanStatus(int visitedEntries, int candidateFiles, int skippedDirectories,
                                  int depthLimitedDirectories, String stopReason, long elapsedMillis) {
        static IndexScanStatus empty() {
            return new IndexScanStatus(0, 0, 0, 0, WorkspaceScanner.StopReason.COMPLETED.name(), 0);
        }

        static IndexScanStatus from(WorkspaceScanner.ScanResult result) {
            return new IndexScanStatus(result.visitedEntries(), result.candidateFiles(), result.skippedDirectories(),
                    result.depthLimitedDirectories(), result.stopReason().name(), result.elapsedMillis());
        }

        public boolean truncated() {
            return !WorkspaceScanner.StopReason.COMPLETED.name().equals(stopReason);
        }
    }

    public record RetrievalHit(String path, String preview, double score, List<String> reasons, List<String> symbols) {
    }

    public record RetrievalResult(List<RetrievalHit> hits, IndexStats indexStats, long elapsedMillis) {
    }

    public record BenchmarkCase(String name, String query, List<String> priorityPaths) {
        public BenchmarkCase {
            priorityPaths = priorityPaths == null ? List.of() : List.copyOf(priorityPaths);
        }
    }

    public record BenchmarkSample(String name, String query, long coldElapsedMillis, long incrementalElapsedMillis,
                                  List<String> coldPaths, List<String> incrementalPaths, double topHitOverlap) {
    }

    public record BenchmarkReport(List<BenchmarkSample> samples) {
        public BenchmarkReport {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }
}
