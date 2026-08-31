package com.labex.labexagent.service;

import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Service;

/**
 * 工作区文本搜索的唯一实现：预算受限的目录遍历 + 逐行正则匹配。
 * Agent 的 GrepTool 与用户侧"在文件夹中查找"共用本服务，禁止各自再写遍历逻辑。
 * 遍历沿用 {@link WorkspaceScanner} 的安全边界与忽略策略（跳过 node_modules/.git/
 * 平台目录等），被跳过的文件仍可正常浏览和打开，只是不参与搜索召回。
 */
@Service
public class WorkspaceTextSearchService {

    private static final long MAX_FILE_BYTES = 300_000;
    private static final long MAX_TOTAL_BYTES = 8_000_000;
    private static final int MAX_LINE_SNIPPET_CHARS = 240;

    private final WorkspaceScanner workspaceScanner;
    private final WorkspaceFileOperationProperties properties;

    public WorkspaceTextSearchService(WorkspaceScanner workspaceScanner,
                                      WorkspaceFileOperationProperties properties) {
        this.workspaceScanner = workspaceScanner;
        this.properties = properties;
    }

    public record SearchQuery(String keyword, boolean regex, boolean caseSensitive, String include, int maxResults) {
    }

    public record SearchHit(String path, int lineNumber, String lineText) {
    }

    public record SearchResult(List<SearchHit> hits, long readBytes, boolean byteBudgetReached,
                               ScannerStopInfo scannerStop, long elapsedMillis) {
        /** true 表示结果完整（含零命中），false 表示因预算提前截断。 */
        public boolean complete() {
            return !byteBudgetReached && scannerStop == null;
        }
    }

    public record ScannerStopInfo(String reason, long visitedEntries, long candidateFiles, long elapsedMillis) {
    }

    /**
     * 在已校验的工作区子目录内搜索。searchRoot 必须来自 resolveExisting，
     * 本方法不重复做路径安全校验，调用方负责所有权与路径边界。
     */
    public SearchResult search(SecureWorkspacePath paths, Path searchRoot, SearchQuery query) {
        Pattern pattern = compilePattern(query);
        int max = Math.min(Math.max(1, query.maxResults()), properties.getSearchMaxResults());
        String include = query.include() == null ? "" : query.include();
        List<SearchHit> hits = new ArrayList<>();
        long[] bytesRead = {0L};
        boolean[] byteBudgetReached = {false};
        long startedAt = System.nanoTime();
        Path workspaceRoot = paths.workspaceRoot();

        WorkspaceScanner.ScanResult scan = null;
        if (Files.isDirectory(searchRoot, LinkOption.NOFOLLOW_LINKS)) {
            try {
                scan = workspaceScanner.scan(paths, searchRoot,
                        new WorkspaceScanner.ScanBudget(10_000, 2_000, 20,
                                Math.max(1_000, properties.getSearchTimeoutMillis())),
                        CancellationToken.none(),
                        (file, attributes) -> collectMatches(file, attributes, searchRoot, workspaceRoot,
                                pattern, include, max, hits, bytesRead, byteBudgetReached));
            } catch (java.io.IOException e) {
                // 忽略扫描 IO 异常，返回已收集的匹配结果
            }
        } else if (Files.isRegularFile(searchRoot, LinkOption.NOFOLLOW_LINKS)) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(searchRoot, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                collectMatches(searchRoot, attributes, searchRoot.getParent(), workspaceRoot, pattern,
                        include, max, hits, bytesRead, byteBudgetReached);
            } catch (Exception ignored) {
                // 单文件不可读视为无命中。
            }
        } else {
            throw new IllegalArgumentException("search root must be an existing file or directory");
        }

        WorkspaceScanner.StopReason reason = scan == null ? null : scan.stopReason();
        ScannerStopInfo stopInfo = reason == null || reason == WorkspaceScanner.StopReason.COMPLETED
                ? null
                : new ScannerStopInfo(reason.name().toLowerCase(Locale.ROOT),
                        scan.visitedEntries(), scan.candidateFiles(), scan.elapsedMillis());
        return new SearchResult(List.copyOf(hits), bytesRead[0], byteBudgetReached[0], stopInfo,
                (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private Pattern compilePattern(SearchQuery query) {
        if (query.keyword() == null || query.keyword().isBlank()) {
            throw new IllegalArgumentException("search keyword is required");
        }
        int flags = query.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE;
        String expression = query.regex() ? query.keyword() : Pattern.quote(query.keyword());
        try {
            return Pattern.compile(expression, flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex: " + e.getDescription());
        }
    }

    // 与原 GrepTool.collectMatches 行为保持一致；修改时必须同步验证 Agent grep 输出格式。
    private boolean collectMatches(Path file, BasicFileAttributes attributes, Path scopeRoot, Path workspaceRoot,
                                   Pattern pattern, String include, int max, List<SearchHit> hits,
                                   long[] bytesRead, boolean[] byteBudgetReached) {
        if (hits.size() >= max || byteBudgetReached[0] || attributes.size() > MAX_FILE_BYTES || isLikelyBinary(file)) {
            return hits.size() < max && !byteBudgetReached[0];
        }
        if (!include.isBlank() && !relative(scopeRoot, file).contains(include)) {
            return true;
        }
        if (bytesRead[0] + attributes.size() > MAX_TOTAL_BYTES) {
            byteBudgetReached[0] = true;
            return false;
        }
        bytesRead[0] += attributes.size();
        String relative = relative(workspaceRoot, file);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && hits.size() < max) {
                lineNumber++;
                if (pattern.matcher(line).find()) {
                    hits.add(new SearchHit(relative, lineNumber, limit(line.trim(), MAX_LINE_SNIPPET_CHARS)));
                }
            }
        } catch (Exception ignored) {
            // 不可读或非 UTF-8 文件不是搜索候选，继续扫描其他安全文件。
        }
        return hits.size() < max;
    }

    private String relative(Path root, Path file) {
        if (root == null || file == null) {
            return "";
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (normalizedRoot.equals(normalizedFile)) {
            Path name = normalizedFile.getFileName();
            return name == null ? "" : name.toString().replace('\\', '/');
        }
        return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
    }

    private boolean isLikelyBinary(Path file) {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(8192);
            for (byte value : bytes) {
                if (value == 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
