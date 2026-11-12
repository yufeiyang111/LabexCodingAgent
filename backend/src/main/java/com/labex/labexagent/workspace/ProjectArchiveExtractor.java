package com.labex.labexagent.workspace;

import com.labex.labexagent.service.ProjectScanPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 项目压缩包解压的唯一实现：路径穿越防护 + 上限判定 + 依赖目录跳过。
 *
 * <p>依赖目录名单复用 {@link ProjectScanPolicy#ignoredDirectoryNames()}，与 Agent 扫描和项目导出
 * 共用同一份，不另立一份。命中的目录整棵跳过（压缩包是流式的，跳过子项只需不写入即可），
 * 因此"带着 node_modules 打包"的项目不会因为依赖体积而超限。
 */
@Component
public class ProjectArchiveExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProjectArchiveExtractor.class);

    /** 跳过的目录名样本上限：只用于让用户看见"跳了什么"，不参与判定。 */
    private static final int SKIPPED_SAMPLE_LIMIT = 5;

    private final ProjectArchiveLimits limits;
    private final Set<String> skippedDirectoryNames;

    public ProjectArchiveExtractor(ProjectArchiveLimits limits) {
        this.limits = limits;
        this.skippedDirectoryNames = Set.copyOf(ProjectScanPolicy.ignoredDirectoryNames());
    }

    /** 解压进度回调：参数是已处理（写入或跳过）的条目数。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onEntryProcessed(int processedEntries);

        ProgressListener NOOP = processedEntries -> { };
    }

    /**
     * 解压结果。{@code skippedCount} 是跳过的文件数（不含目录条目），{@code skippedSamples} 是前几个
     * 被跳过文件的相对路径，供前端展示具体跳过了什么。
     */
    public record ExtractStats(int fileCount, long totalSize, int skippedCount, List<String> skippedSamples) {
        public ExtractStats {
            skippedSamples = List.copyOf(skippedSamples);
        }
    }

    /**
     * 压缩包内的条目总数（含目录条目）。读的是 zip 中央目录，不解压，代价与文件数成正比但不碰内容。
     * 用于把"已处理多少条目"变成有分母的百分比。
     */
    public int countEntries(Path archivePath) throws IOException {
        int total = 0;
        try (ZipFile zipFile = new ZipFile(archivePath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                entries.nextElement();
                total++;
            }
        }
        return total;
    }

    /**
     * 解压到 {@code targetDir}。
     *
     * @param skipDependencyDirectories 是否跳过依赖与构建产物目录（用户可取消勾选）
     * @throws IllegalArgumentException 路径穿越、超出字节上限或文件数上限
     */
    public ExtractStats extract(Path archivePath, Path targetDir, boolean skipDependencyDirectories)
            throws IOException {
        return extract(archivePath, targetDir, skipDependencyDirectories, ProgressListener.NOOP);
    }

    public ExtractStats extract(Path archivePath, Path targetDir, boolean skipDependencyDirectories,
                                ProgressListener listener) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        int fileCount = 0;
        int skippedCount = 0;
        int processed = 0;
        long totalSize = 0L;
        List<String> skippedSamples = new ArrayList<>();

        try (InputStream inputStream = Files.newInputStream(archivePath);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String entryName = entry.getName();
                processed++;
                if (skipDependencyDirectories && hasSkippedSegment(entryName)) {
                    if (!entry.isDirectory()) {
                        skippedCount++;
                        if (skippedSamples.size() < SKIPPED_SAMPLE_LIMIT) {
                            // PowerShell 的 Compress-Archive 用反斜杠分隔，展示时统一成正斜杠。
                            skippedSamples.add(entryName.replace('\\', '/'));
                        }
                    }
                    zipInputStream.closeEntry();
                    reportProgress(listener, processed);
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolveEntry(normalizedTarget, entryName));
                    zipInputStream.closeEntry();
                    reportProgress(listener, processed);
                    continue;
                }
                fileCount++;
                if (fileCount > limits.getMaxExtractedFiles()) {
                    throw new IllegalArgumentException("项目文件数超过上限 " + limits.getMaxExtractedFiles()
                            + " 个，请删除依赖与构建产物目录后重新打包");
                }
                Path outputPath = resolveEntry(normalizedTarget, entryName);
                Files.createDirectories(outputPath.getParent());
                long copied = Files.copy(zipInputStream, outputPath);
                totalSize += copied;
                if (totalSize > limits.getMaxExtractedBytes()) {
                    throw new IllegalArgumentException("项目解压后超过上限 "
                            + formatMegabytes(limits.getMaxExtractedBytes()) + " MB，请删除依赖与构建产物目录后重新打包");
                }
                zipInputStream.closeEntry();
                reportProgress(listener, processed);
            }
        }
        if (fileCount == 0) {
            // 损坏的 zip 与空 zip 在 ZipInputStream 看来都是「没有条目」，两者都不该建出一个空项目。
            throw new IllegalArgumentException(skippedCount > 0
                    ? "压缩包内只有依赖或构建产物文件（已跳过 " + skippedCount + " 个），没有可导入的源文件"
                    : "压缩包内没有可导入的文件，请确认它是有效的 .zip");
        }
        log.info("PROJECT_ARCHIVE_EXTRACTED archive={} files={} bytes={} skipped={}",
                archivePath.getFileName(), fileCount, totalSize, skippedCount);
        return new ExtractStats(fileCount, totalSize, skippedCount, skippedSamples);
    }
    private static void reportProgress(ProgressListener listener, int processed) {
        if (listener != null) {
            listener.onEntryProcessed(processed);
        }
    }

    /** 路径里任意一段命中内置忽略目录名，即整条跳过。 */
    private boolean hasSkippedSegment(String entryName) {
        for (String segment : entryName.replace('\\', '/').split("/")) {
            if (skippedDirectoryNames.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private Path resolveEntry(Path targetDir, String entryName) {
        String normalizedEntryName = entryName.replace('\\', '/');
        Path resolved = targetDir.resolve(normalizedEntryName).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new IllegalArgumentException("Unsafe archive entry detected: " + entryName);
        }
        return resolved;
    }

    static String formatMegabytes(long bytes) {
        return String.valueOf(Math.round(bytes / (1024.0 * 1024.0)));
    }
}
