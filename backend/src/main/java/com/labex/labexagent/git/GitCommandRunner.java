package com.labex.labexagent.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 所有 git 子进程的唯一入口：超时、输出上限、环境隔离都在这里。
 *
 * <p>为什么集中：快照、用户暂存、clone 服务三条路径都要跑 git，各自拼 ProcessBuilder 会出现
 * 超时不一致、输出无上限、{@code GIT_INDEX_FILE} 忘记清理等问题。任何新代码都必须走这里，
 * 禁止再写第二份进程封装。
 *
 * <p>{@code core.*} 参数由调用方通过 {@code --git-dir}/{@code --work-tree} 指定仓库，
 * 本类不猜仓库位置。
 */
@Service
public class GitCommandRunner {
    private static final Logger log = LoggerFactory.getLogger(GitCommandRunner.class);
    /** 单次 git 输出上限：超过即截断，防止 `git log`/`git diff` 撑爆堆内存。 */
    public static final int MAX_OUTPUT = 120_000;
    /** 截断标记。同步供给据此判定内容完整性，被截断的内容不能作为回退依据。 */
    public static final String TRUNCATION_MARKER = "\n...[truncated]";

    private static final List<String> REPOSITORY_OPTIONS = List.of(
            "-c", "core.longpaths=true",
            "-c", "core.symlinks=true",
            "-c", "core.autocrlf=false",
            "-c", "core.quotepath=false");

    public CommandResult run(Path workingDirectory, List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        return run(workingDirectory, command, timeoutSeconds, null);
    }

    /**
     * 执行一条 git 命令。
     *
     * @param indexFile 非 null 时通过 {@code GIT_INDEX_FILE} 指向独立索引；null 表示用仓库默认索引
     */
    public CommandResult run(Path workingDirectory, List<String> command, int timeoutSeconds, Path indexFile)
            throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        String operation = gitOperation(command);
        log.info("GIT_COMMAND_START operation={} timeoutSeconds={} indexMode={}",
                operation, timeoutSeconds, indexFile == null ? "default" : "override");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        if (indexFile != null) {
            builder.environment().put("GIT_INDEX_FILE", indexFile.toString());
        }
        Process process = builder.start();
        OutputCollector collector = new OutputCollector(process.getInputStream(), MAX_OUTPUT);
        Thread reader = new Thread(collector, "labex-git-output");
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            reader.join(2_000);
            log.warn("GIT_COMMAND_TIMEOUT operation={} timeoutSeconds={} elapsedMs={} outputChars={}",
                    operation, timeoutSeconds, elapsedMs(startedNanos), collector.output().length());
            throw new IOException("git command timeout");
        }
        reader.join(2_000);
        if (reader.isAlive()) {
            throw new IOException("git command output reader did not finish");
        }
        if (collector.error() != null) {
            throw collector.error();
        }
        String output = collector.output();
        if (process.exitValue() != 0) {
            log.warn("GIT_COMMAND_FAILED operation={} exitCode={} elapsedMs={} outputChars={} truncated={}",
                    operation, process.exitValue(), elapsedMs(startedNanos), output.length(), collector.truncated());
            throw new IOException(limit(output, 4000));
        }
        log.info("GIT_COMMAND_COMPLETE operation={} exitCode={} elapsedMs={} outputChars={} truncated={}",
                operation, process.exitValue(), elapsedMs(startedNanos), output.length(), collector.truncated());
        return new CommandResult(process.exitValue(), output);
    }

    /**
     * 针对指定仓库执行 git 命令：自动补上 {@code --git-dir} / {@code --work-tree} 与统一的
     * {@code core.*} 参数。工作目录设为 work tree，使相对 pathspec 解析符合直觉。
     */
    public CommandResult git(Path workTree, Path gitDir, int timeoutSeconds, List<String> args) throws IOException, InterruptedException {
        return git(workTree, gitDir, timeoutSeconds, args, null);
    }

    public CommandResult git(Path workTree, Path gitDir, int timeoutSeconds, List<String> args, Path indexFile)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(REPOSITORY_OPTIONS);
        command.add("--git-dir");
        command.add(gitDir.toString());
        command.add("--work-tree");
        command.add(workTree.toString());
        command.addAll(args);
        return run(workTree, command, timeoutSeconds, indexFile);
    }

    public static String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max) + TRUNCATION_MARKER;
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private String gitOperation(List<String> command) {
        for (String value : command) {
            if (List.of("add", "status", "commit", "rev-parse", "diff", "config", "init", "checkout", "reset",
                    "read-tree", "write-tree", "cat-file", "show", "ls-tree", "log", "for-each-ref",
                    "update-index", "symbolic-ref", "gc", "rev-list", "diff-tree").contains(value)) {
                return value;
            }
        }
        return command == null || command.isEmpty() ? "unknown" : command.get(0);
    }

    public record CommandResult(int exitCode, String output) {
        public String trimmed() {
            return output == null ? "" : output.trim();
        }
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicReference<IOException> error = new AtomicReference<>();
        private volatile boolean truncated;

        private OutputCollector(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8_192];
            try (InputStream stream = input) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int remaining = limit - output.size();
                    if (remaining > 0) {
                        output.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                error.set(e);
            }
        }

        private String output() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private IOException error() {
            return error.get();
        }

        private boolean truncated() {
            return truncated;
        }
    }
}
