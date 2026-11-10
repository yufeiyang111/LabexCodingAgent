package com.labex.labexagent.worker;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkerPolicy(
        String containerImage,
        int cpuMillis,
        int memoryMegabytes,
        int maxPids,
        boolean networkEnabled) {

    private static final List<String> HOST_ENVIRONMENT_ALLOWLIST = List.of(
            "PATH", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT", "TMP", "TEMP", "LANG", "LC_ALL");

    public WorkerPolicy {
        if (containerImage == null || containerImage.isBlank()) {
            throw new IllegalArgumentException("containerImage is required");
        }
        if (cpuMillis < 100 || memoryMegabytes < 128 || maxPids < 16) {
            throw new IllegalArgumentException("worker resource limits are too small");
        }
    }

    public static WorkerPolicy defaults() {
        return WorkerResourceDefaults.defaults();
    }

    /** 无 Spring 上下文时的兜底默认值，只用于纯单元测试路径。网络默认开启。 */
    static WorkerPolicy fallbackDefaults() {
        return new WorkerPolicy("labex-agent-sandbox:latest", 1_000, 1_024, 256, true);
    }

    /** 返回具有指定网络能力的新策略，不修改默认的离线策略。 */
    public WorkerPolicy withNetworkEnabled(boolean enabled) {
        return new WorkerPolicy(containerImage, cpuMillis, memoryMegabytes, maxPids, enabled);
    }

    /**
     * 命令执行环境。
     *
     * <p>{@code GIT_CEILING_DIRECTORIES} 指向工作区本身：git 向上查找仓库时到这一层就停，
     * 不会跑到工作区外面去。没有它时，工作区里跑 {@code git log} 会撞上外层目录的仓库
     * （用户把项目放在自己的仓库里、或平台把工作区放在别的仓库下面时），Agent 会报出别人的历史。
     * 平台自己的仓库由工作区的 {@code .git} gitfile 指向，不受这条限制影响。
     */
    public Map<String, String> safeEnvironment(Path workspace, Map<String, String> hostEnvironment) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : HOST_ENVIRONMENT_ALLOWLIST) {
            String value = findIgnoreCase(hostEnvironment, name);
            if (value != null && !value.isBlank()) {
                environment.put(name, value);
            }
        }
        Path runtimeRoot = workspace.toAbsolutePath().normalize().resolve(".labex-agent").resolve("runtime");
        String homePath = runtimeRoot.resolve("home").toString();
        environment.put("HOME", homePath);
        environment.put("USERPROFILE", homePath);
        environment.put("APPDATA", runtimeRoot.resolve("appdata").toString());
        environment.put("LOCALAPPDATA", runtimeRoot.resolve("localappdata").toString());
        environment.put("NPM_CONFIG_CACHE", runtimeRoot.resolve("npm-cache").toString());
        environment.put("XDG_CACHE_HOME", runtimeRoot.resolve("cache").toString());
        environment.put("TERM", "xterm-256color");
        environment.put("LANG", environment.getOrDefault("LANG", "C.UTF-8"));
        environment.put("LC_ALL", environment.getOrDefault("LC_ALL", "C.UTF-8"));
        // 容器/沙箱内的路径与宿主不同（/workspace、/mnt/d/...），由各 Worker 自己覆盖。
        environment.put("GIT_CEILING_DIRECTORIES", gitCeiling(workspace));
        return environment;
    }

    /**
     * git 查找仓库的上界：工作区的**父目录**。
     *
     * <p>{@code GIT_CEILING_DIRECTORIES} 的语义是「不要搜索这些目录及其祖先」，而当前工作目录
     * 永远会被搜索。所以给工作区本身没有用——当前目录照样查，上一级照样往上找；给父目录才真的
     * 把查找挡在工作区里面。工作区自己的 {@code .git} gitfile 仍在当前目录被命中。
     */
    static String gitCeiling(Path workspace) {
        Path parent = workspace.toAbsolutePath().normalize().getParent();
        return parent == null ? "" : parent.toString();
    }

    private String findIgnoreCase(Map<String, String> environment, String name) {
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
