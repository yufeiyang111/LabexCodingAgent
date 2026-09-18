package com.labex.labexagent.workspace;

import com.labex.labexagent.service.ProjectScanPolicy;
import java.nio.file.Path;
import java.util.List;

/**
 * 项目导出/下载的唯一排除判定入口。
 *
 * <p>排除来源固定为三层，顺序与语义只在这里定义一次；任何导出链路
 * （同步 {@code GET /{projectId}/export}、异步导出任务 {@code ProjectExportJobService}、
 * 以及后续新增的打包/下载链路）都必须复用本类，禁止各自再实现一遍遍历 + 判断。
 *
 * <ol>
 *   <li><b>平台保护区</b>：{@code .labex/}、{@code .labex-agent/}、{@code .labex-rename-*}，
 *       任何模式下都排除，不接受任何开关（{@link ProtectedWorkspacePaths}）。</li>
 *   <li><b>.labex-agentignore 声明的条目</b>：与 Agent 扫描共用
 *       {@link ProjectScanPolicy#loadDeclaredIgnoreRules} 的解析与 glob 实现；
 *       任何模式下都排除，{@code includeAll} 也不放行——"Agent 看不到的文件不进导出包"。</li>
 *   <li><b>依赖/构建产物目录名</b>：{@code labex-agent.file-ops.export-excluded-directory-names}，
 *       仅在 {@code includeAll} 为 false 时排除。</li>
 * </ol>
 *
 * <p><b>副作用（必须知道）</b>：{@code .labex-agentignore} 的默认内容由
 * {@link ProjectScanPolicy#defaultIgnoreFileContent()} 生成，逐条列出了内置忽略目录名，
 * 而内置目录名与 {@code exportExcludedDirectoryNames} 基本重合。
 * 因此对"从未编辑过忽略文件"的工作区，勾选"包含全部文件"能放行的实际只剩
 * {@code exportExcludedDirectoryNames} 中不在忽略文件里的项；想放行 {@code node_modules}
 * 需先在 {@code .labex-agentignore} 中删掉对应行。
 *
 * <p>反向选择（{@code includeAll} 连忽略文件一起绕过）只需把本类
 * {@link #shouldSkipEntry} 中的 {@code includeAll} 短路提到声明规则之前，但会让
 * 用户在忽略文件里标记的敏感文件随"包含全部"外泄，故默认不采用。
 * 若确实需要"不可绕过的敏感文件黑名单"（例如 {@code .env} / {@code secrets/}），
 * 应新增独立配置项，不要复用 .labex-agentignore。
 */
public final class ExportSelectionPolicy {

    private final Path root;
    private final ProjectScanPolicy.ScanIgnoreRules declaredIgnoreRules;
    private final boolean includeAll;
    private final List<String> excludedDirectoryNames;

    private ExportSelectionPolicy(Path root, ProjectScanPolicy.ScanIgnoreRules declaredIgnoreRules,
                                  boolean includeAll, List<String> excludedDirectoryNames) {
        this.root = root;
        this.declaredIgnoreRules = declaredIgnoreRules;
        this.includeAll = includeAll;
        this.excludedDirectoryNames = excludedDirectoryNames;
    }

    /**
     * 按工作区构建一次导出判定。会读取一次 .labex-agentignore（文件缺失/异常时退化为空规则），
     * 因此在同一次打包里应只构建一次并复用。
     */
    public static ExportSelectionPolicy forWorkspace(SecureWorkspacePath paths,
                                                     WorkspaceFileOperationProperties properties,
                                                     boolean includeAll) {
        return new ExportSelectionPolicy(paths.workspaceRoot(),
                ProjectScanPolicy.loadDeclaredIgnoreRules(paths),
                includeAll,
                properties.getExportExcludedDirectoryNames());
    }

    /**
     * 该条目是否不参与打包。目录命中时调用方应 SKIP_SUBTREE，文件命中时跳过单文件。
     *
     * @param directory 由调用方依据已读取的属性判断，避免在遍历内部再落一次磁盘
     */
    public boolean shouldSkipEntry(Path entry, boolean directory) {
        if (root.equals(entry)) {
            return false;
        }
        if (ProtectedWorkspacePaths.isProtectedEntry(root, entry)) {
            return true;
        }
        if (declaredIgnoreRules.shouldSkipEntry(entry, directory)) {
            return true;
        }
        if (includeAll) {
            return false;
        }
        String candidateName = directory ? String.valueOf(entry.getFileName()) : parentDirectoryName(entry);
        return candidateName != null && isExcludedDirectoryName(candidateName);
    }

    private static String parentDirectoryName(Path entry) {
        Path parent = entry.getParent();
        return parent == null ? null : String.valueOf(parent.getFileName());
    }

    private boolean isExcludedDirectoryName(String name) {
        return excludedDirectoryNames.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(name));
    }

    /** 供日志/诊断使用：本次是否真的读到了用户声明的规则（用于区分"忽略文件缺失"与"文件为空"）。 */
    public boolean hasDeclaredRules() {
        return declaredIgnoreRules.hasPatterns();
    }
}
