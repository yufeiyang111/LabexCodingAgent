package com.labex.labexagent.terminal;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TerminalEnvironment {
    private static final List<String> HOST_VARIABLES = List.of(
            "PATH", "SystemRoot", "WINDIR", "COMSPEC", "PATHEXT", "TMP", "TEMP", "LANG", "LC_ALL");

    private TerminalEnvironment() {
    }

    static Map<String, String> forWorkspace(Path workspace, int cols, int rows, Map<String, String> hostEnvironment) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : HOST_VARIABLES) {
            String value = findIgnoreCase(hostEnvironment, name);
            if (value != null && !value.isBlank()) {
                environment.put(name, value);
            }
        }

        Path runtimeRoot = runtimeRoot(workspace);
        String homePath = runtimeRoot.resolve("home").toString();
        environment.put("HOME", homePath);
        environment.put("USERPROFILE", homePath);
        environment.put("APPDATA", runtimeRoot.resolve("appdata").toString());
        environment.put("LOCALAPPDATA", runtimeRoot.resolve("localappdata").toString());
        environment.put("NPM_CONFIG_CACHE", runtimeRoot.resolve("npm-cache").toString());
        environment.put("XDG_CACHE_HOME", runtimeRoot.resolve("cache").toString());
        environment.put("TERM", "xterm-256color");
        environment.put("COLORTERM", "truecolor");
        environment.put("COLUMNS", String.valueOf(cols));
        environment.put("LINES", String.valueOf(rows));
        environment.put("LANG", environment.getOrDefault("LANG", "C.UTF-8"));
        environment.put("LC_ALL", environment.getOrDefault("LC_ALL", "C.UTF-8"));
        environment.put("PS1", "\\[\\033[36m\\]\\w\\[\\033[0m\\]\\n\\[\\033[32m\\]> \\[\\033[0m\\]");
        return environment;
    }

    static Path runtimeRoot(Path workspace) {
        return workspace.toAbsolutePath().normalize().resolve(".labex-agent").resolve("runtime");
    }

    private static String findIgnoreCase(Map<String, String> environment, String name) {
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
