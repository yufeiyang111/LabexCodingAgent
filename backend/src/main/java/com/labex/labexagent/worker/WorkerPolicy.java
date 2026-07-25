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
        return new WorkerPolicy("labex-agent-sandbox:latest", 1_000, 1_024, 256, false);
    }

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
        return environment;
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
