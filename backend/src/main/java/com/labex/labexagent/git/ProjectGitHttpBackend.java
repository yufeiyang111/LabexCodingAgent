package com.labex.labexagent.git;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把 {@code git clone} 的 HTTP 请求转给 {@code git http-backend}。
 *
 * <p>为什么不自己实现 git 协议：smart HTTP 协议包含 ref 广告、packfile 协商、增量压缩，
 * 自己实现等于重写 git。{@code git-http-backend} 是 git 官方提供的 CGI 程序，负责这一切，
 * 我们只需要把请求转成 CGI 环境变量、把输出转回 HTTP。
 *
 * <p>只读：{@code GIT_HTTP_EXPORT_ALL} 与 {@code http.receivepack=false} 一起保证这条路
 * 只能 clone/fetch，不能 push。push 需要冲突合并策略，不在本轮范围。
 */
@Service
public class ProjectGitHttpBackend {
    private static final Logger log = LoggerFactory.getLogger(ProjectGitHttpBackend.class);
    private static final int MAX_REQUEST_BYTES = 64 * 1024 * 1024;
    private static final int BACKEND_TIMEOUT_SECONDS = 300;

    private final ProjectGitRepository repository;

    public ProjectGitHttpBackend(ProjectGitRepository repository) {
        this.repository = repository;
    }

    /**
     * 处理一次 git HTTP 请求。
     *
     * @param project     已鉴权的项目
     * @param pathInfo    请求路径里仓库之后的部分，例如 {@code /info/refs} 或 {@code /git-upload-pack}
     * @param queryString 原始查询串（{@code service=git-upload-pack}）
     */
    public void handle(StudentProject project, String pathInfo, String queryString,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        Path root = ProjectWorkspace.paths(project).workspaceRoot();
        Path gitDir = repository.gitDir(root);
        if (!Files.exists(gitDir.resolve("HEAD"))) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "repository not initialized");
            return;
        }
        Path backend = locateBackend();
        if (backend == null) {
            log.error("git-http-backend not found; git clone is unavailable on this host");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "git http backend unavailable");
            return;
        }

        String requestMethod = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase(Locale.ROOT);
        Map<String, String> env = new LinkedHashMap<>();
        // http-backend 把 PATH_INFO 直接拼在 GIT_PROJECT_ROOT 后面定位仓库，
        // 所以根目录是仓库的父目录，PATH_INFO 必须以仓库目录名开头。
        env.put("GIT_PROJECT_ROOT", gitDir.getParent().toString());
        env.put("GIT_HTTP_EXPORT_ALL", "1");
        env.put("PATH_INFO", "/" + gitDir.getFileName() + safePathInfo(pathInfo));
        env.put("REQUEST_METHOD", requestMethod);
        env.put("QUERY_STRING", queryString == null ? "" : queryString);
        env.put("CONTENT_TYPE", nullToEmpty(request.getContentType()));
        env.put("REMOTE_USER", "labex");
        env.put("REMOTE_ADDR", nullToEmpty(request.getRemoteAddr()));
        env.put("SERVER_PROTOCOL", nullToEmpty(request.getProtocol()));
        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("PATH", nullToEmpty(System.getenv("PATH")));

        ProcessBuilder builder = new ProcessBuilder(backend.toString());
        builder.environment().putAll(env);
        builder.redirectErrorStream(false);
        Process process = builder.start();

        byte[] body = readBody(request);
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(body);
        } catch (IOException clientClosed) {
            log.debug("git http backend stdin closed early: {}", clientClosed.getMessage());
        }

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread errReader = drain(process.getErrorStream(), stderr, "git-http-stderr");

        boolean finished;
        try {
            // 正文直接流式转发：pack 可能有几百 MB，整段读进内存会撑爆堆。
            streamCgiResponse(process.getInputStream(), response);
            finished = process.waitFor(BACKEND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("git http backend interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            log.warn("git http backend timeout projectId={} path={}", project.getProjectId(), pathInfo);
            return;
        }
        joinQuietly(errReader);
        if (stderr.size() > 0) {
            log.debug("git http backend stderr projectId={} message={}",
                    project.getProjectId(), stderr.toString(StandardCharsets.UTF_8).trim());
        }
    }

    /**
     * 逐行读 CGI 头部，遇到空行后把剩余字节直接写进响应体。
     *
     * <p>不做整段缓冲：{@code git clone} 的 packfile 大小与仓库成正比，先收全再发会把
     * 一次 clone 的内存占用变成仓库体积的函数。
     */
    private void streamCgiResponse(InputStream stdout, HttpServletResponse response) throws IOException {
        StringBuilder header = new StringBuilder();
        OutputStream out = response.getOutputStream();
        int pending = -1;
        while (true) {
            int value = stdout.read();
            if (value == -1) {
                break;
            }
            if (value == '\n') {
                String line = header.toString().replace("\r", "");
                header.setLength(0);
                if (line.isEmpty()) {
                    break;
                }
                applyHeader(line, response);
                continue;
            }
            header.append((char) value);
            if (header.length() > 8_192) {
                throw new IOException("git http backend sent an oversized header line");
            }
        }
        if (header.length() > 0) {
            // 头部没有以空行结束：说明后端异常退出，按 500 返回已收到的内容。
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        }
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = stdout.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private void applyHeader(String line, HttpServletResponse response) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String name = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        if ("Status".equalsIgnoreCase(name)) {
            response.setStatus(parseStatus(value));
        } else if (!"Content-Length".equalsIgnoreCase(name)) {
            // 长度由容器按实际写出计算；CGI 报的长度在流式转发下不再可信。
            response.setHeader(name, value);
        }
    }

    private int parseStatus(String value) {
        int space = value.indexOf(' ');
        String code = space > 0 ? value.substring(0, space) : value;
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            return HttpServletResponse.SC_OK;
        }
    }

    private int indexOfHeaderEnd(byte[] raw) {
        for (int index = 0; index < raw.length - 1; index++) {
            if (raw[index] == '\r' && raw[index + 1] == '\n'
                    && index + 3 < raw.length && raw[index + 2] == '\r' && raw[index + 3] == '\n') {
                return index;
            }
            if (raw[index] == '\n' && raw[index + 1] == '\n') {
                return index;
            }
        }
        return -1;
    }


    private byte[] readBody(HttpServletRequest request) throws IOException {
        try (InputStream input = request.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw new IOException("git request body exceeds limit");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private Thread drain(InputStream stream, ByteArrayOutputStream sink, String name) {
        Thread thread = new Thread(() -> {
            byte[] chunk = new byte[16 * 1024];
            try (InputStream input = stream) {
                int read;
                while ((read = input.read(chunk)) != -1) {
                    synchronized (sink) {
                        sink.write(chunk, 0, read);
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时管道会关闭，这不是错误
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String safePathInfo(String pathInfo) {
        String value = pathInfo == null || pathInfo.isBlank() ? "/" : pathInfo;
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        // http-backend 会把 PATH_INFO 拼到 GIT_PROJECT_ROOT 后面，任何上跳都必须挡掉。
        if (value.contains("..") || value.contains("\\")) {
            return "/";
        }
        return value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Path locateBackend() {
        String execPath = System.getenv("GIT_EXEC_PATH");
        List<Path> candidates = new ArrayList<>();
        if (execPath != null && !execPath.isBlank()) {
            candidates.add(Path.of(execPath, executableName()));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry, executableName()));
                }
            }
        }
        candidates.add(Path.of("D:/Git/mingw64/libexec/git-core", executableName()));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String executableName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "git-http-backend.exe"
                : "git-http-backend";
    }
}
