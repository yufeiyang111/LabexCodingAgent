package com.labex.labexagent.commandsecurity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 根据服务端策略解析固定的直接 argv 验证命令，不接受模型传入的 shell 字符串。 */
public final class TestCommandResolver {
    private TestCommandResolver() { }

    public static List<String> resolve(Path workspaceRoot) {
        return resolveProject(workspaceRoot, VerificationStrategy.AUTO).command();
    }

    public static ResolvedTestCommand resolveProject(Path workspaceRoot) {
        return resolveProject(workspaceRoot, VerificationStrategy.AUTO);
    }

    public static ResolvedTestCommand resolveProject(Path workspaceRoot, VerificationStrategy strategy) {
        return resolveProject(workspaceRoot, strategy, null);
    }

    public static ResolvedTestCommand resolveProject(Path workspaceRoot, VerificationStrategy strategy,
                                                     VerificationStrategy fallbackStrategy) {
        return resolveProject(workspaceRoot, strategy, fallbackStrategy, List.of());
    }

    public static ResolvedTestCommand resolveProject(Path workspaceRoot, VerificationStrategy strategy,
                                                     VerificationStrategy fallbackStrategy,
                                                     Collection<String> preferredTargets) {
        VerificationStrategy selected = strategy == null ? VerificationStrategy.AUTO : strategy;
        ResolvedTestCommand targeted = resolvePreferredProject(
                workspaceRoot, selected, fallbackStrategy, preferredTargets);
        if (targeted != null) {
            return targeted;
        }
        ResolvedTestCommand primary = resolveProjectInternal(workspaceRoot, selected);
        if (!primary.command().isEmpty() || fallbackStrategy == null || fallbackStrategy == selected
                || fallbackStrategy == VerificationStrategy.MANUAL) {
            return primary;
        }
        return resolveProjectInternal(workspaceRoot, fallbackStrategy);
    }

    private static ResolvedTestCommand resolvePreferredProject(
            Path workspaceRoot, VerificationStrategy strategy, VerificationStrategy fallbackStrategy,
            Collection<String> preferredTargets) {
        if (workspaceRoot == null || preferredTargets == null || preferredTargets.isEmpty()) {
            return null;
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<Path> targetPaths = new ArrayList<>();
        Set<Path> projectRoots = new LinkedHashSet<>();
        for (String target : preferredTargets) {
            if (target == null || target.isBlank()) {
                continue;
            }
            Path candidate = safeTarget(root, target);
            if (candidate == null) {
                return ResolvedTestCommand.unsupported(root, "target_path 必须是项目内的安全相对路径");
            }
            targetPaths.add(candidate);
            Path projectRoot = nearestProjectRoot(root, candidate);
            if (projectRoot != null) {
                projectRoots.add(projectRoot);
            }
        }
        if (projectRoots.isEmpty()) {
            return null;
        }
        if (projectRoots.size() > 1) {
            String modules = projectRoots.stream()
                    .map(path -> relativeDisplay(root, path))
                    .sorted()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            return ResolvedTestCommand.unsupported(root,
                    "改动跨越多个模块（" + modules + "）；请使用 target_path 分别验证，避免把单一模块成功误报为整个任务通过");
        }
        Path projectRoot = projectRoots.iterator().next();
        List<String> command = targetPaths.stream()
                .map(target -> commandForExplicitManifest(projectRoot, target, strategy))
                .filter(candidate -> !candidate.isEmpty())
                .findFirst()
                .orElseGet(() -> commandFor(projectRoot, strategy));
        if (command.isEmpty() && fallbackStrategy != null && fallbackStrategy != strategy
                && fallbackStrategy != VerificationStrategy.MANUAL) {
            VerificationStrategy fallback = fallbackStrategy;
            command = targetPaths.stream()
                    .map(target -> commandForExplicitManifest(projectRoot, target, fallback))
                    .filter(candidate -> !candidate.isEmpty())
                    .findFirst()
                    .orElseGet(() -> commandFor(projectRoot, fallback));
        }
        if (command.isEmpty()) {
            return ResolvedTestCommand.unsupported(projectRoot,
                    "目标模块在当前验证策略下没有可用命令");
        }
        return new ResolvedTestCommand(projectRoot, command);
    }

    private static Path safeTarget(Path root, String target) {
        try {
            String normalized = target.trim().replace('\\', '/');
            if (normalized.startsWith("/") || normalized.matches("(?i)^[a-z]:.*")) {
                return null;
            }
            Path relative = Path.of(normalized).normalize();
            if (relative.startsWith("..")) {
                return null;
            }
            Path resolved = root.resolve(relative).normalize();
            return resolved.startsWith(root) ? resolved : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Path nearestProjectRoot(Path root, Path target) {
        Path current = target;
        while (current != null && current.startsWith(root)) {
            if (isProjectRoot(current)) {
                return current;
            }
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isProjectRoot(Path path) {
        return Files.exists(path.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(path.resolve("package.json"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(path.resolve("build.gradle"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(path.resolve("build.gradle.kts"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(path.resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(path.resolve("pyproject.toml"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(path.resolve("pytest.ini"), LinkOption.NOFOLLOW_LINKS);
    }

    private static String relativeDisplay(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private static ResolvedTestCommand resolveProjectInternal(Path workspaceRoot, VerificationStrategy strategy) {
        if (workspaceRoot == null) return ResolvedTestCommand.unsupported(null);
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<String> rootCommand = commandFor(root, strategy);
        if (!rootCommand.isEmpty()) return new ResolvedTestCommand(root, rootCommand);
        if (strategy == VerificationStrategy.MANUAL) return ResolvedTestCommand.unsupported(root);
        try (var children = Files.list(root)) {
            return children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> new ResolvedTestCommand(path, commandFor(path, strategy)))
                    .filter(resolved -> !resolved.command().isEmpty())
                    .findFirst().orElse(ResolvedTestCommand.unsupported(root));
        } catch (IOException ignored) {
            return ResolvedTestCommand.unsupported(root);
        }
    }

    public static String canonicalCommand(Path workspaceRoot) {
        return canonicalCommand(workspaceRoot, VerificationStrategy.AUTO);
    }

    public static String canonicalCommand(Path workspaceRoot, VerificationStrategy strategy) {
        return String.join(" ", resolveProject(workspaceRoot, strategy).command());
    }

    private static List<String> npmVerificationCommand(Path packageJson, VerificationStrategy strategy) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(packageJson)).getAsJsonObject();
            JsonObject scripts = root.has("scripts") && root.get("scripts").isJsonObject()
                    ? root.getAsJsonObject("scripts") : new JsonObject();
            boolean hasTest = scripts.has("test") && !scripts.get("test").getAsString().isBlank();
            boolean hasBuild = scripts.has("build") && !scripts.get("build").getAsString().isBlank();
            return switch (strategy) {
                case TEST, OFFLINE_TEST -> hasTest ? List.of("npm", "test") : List.of();
                case COMPILE, BUILD -> hasBuild ? List.of("npm", "run", "build") : List.of();
                case AUTO -> hasTest ? List.of("npm", "test") : hasBuild ? List.of("npm", "run", "build") : List.of();
                case MANUAL -> List.of();
            };
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** 显式 manifest 目标优先于同目录中的其他技术栈，避免多栈工作区选错验证器。 */
    private static List<String> commandForExplicitManifest(Path projectRoot, Path target,
                                                           VerificationStrategy strategy) {
        if (projectRoot == null || target == null || strategy == VerificationStrategy.MANUAL) {
            return List.of();
        }
        Path normalized = target.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                || normalized.getParent() == null
                || !normalized.getParent().equals(projectRoot.toAbsolutePath().normalize())) {
            return List.of();
        }
        String fileName = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (fileName) {
            case "pom.xml" -> mavenVerificationCommand(strategy);
            case "package.json" -> npmVerificationCommand(normalized, strategy);
            case "build.gradle", "build.gradle.kts" -> gradleVerificationCommand(strategy);
            case "requirements.txt", "pyproject.toml" -> pythonVerificationCommand(strategy);
            case "pytest.ini" -> List.of("pytest");
            default -> List.of();
        };
    }

    private static List<String> commandFor(Path projectRoot, VerificationStrategy strategy) {
        if (strategy == VerificationStrategy.MANUAL) return List.of();
        if (Files.exists(projectRoot.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
            return mavenVerificationCommand(strategy);
        }
        if (Files.exists(projectRoot.resolve("package.json"), LinkOption.NOFOLLOW_LINKS)) {
            return npmVerificationCommand(projectRoot.resolve("package.json"), strategy);
        }
        if (Files.exists(projectRoot.resolve("build.gradle"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(projectRoot.resolve("build.gradle.kts"), LinkOption.NOFOLLOW_LINKS)) {
            return gradleVerificationCommand(strategy);
        }
        if (Files.exists(projectRoot.resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(projectRoot.resolve("pyproject.toml"), LinkOption.NOFOLLOW_LINKS)) {
            return pythonVerificationCommand(strategy);
        }
        if (Files.exists(projectRoot.resolve("pytest.ini"), LinkOption.NOFOLLOW_LINKS)) return List.of("pytest");
        return List.of();
    }

    private static List<String> mavenVerificationCommand(VerificationStrategy strategy) {
        return switch (strategy) {
            case COMPILE -> List.of("mvn", "compile");
            case BUILD -> List.of("mvn", "package", "-DskipTests");
            case OFFLINE_TEST -> List.of("mvn", "-o", "test");
            case TEST, AUTO -> List.of("mvn", "test");
            case MANUAL -> List.of();
        };
    }

    private static List<String> gradleVerificationCommand(VerificationStrategy strategy) {
        return strategy == VerificationStrategy.COMPILE
                ? List.of("./gradlew", "classes")
                : List.of("./gradlew", "test");
    }

    private static List<String> pythonVerificationCommand(VerificationStrategy strategy) {
        return strategy == VerificationStrategy.COMPILE
                ? List.of("python", "-m", "compileall", ".")
                : List.of("python", "-m", "pytest");
    }

    public static int defaultTimeoutSeconds(List<String> command) {
        if (command == null || command.isEmpty()) {
            return 120;
        }
        String executable = command.get(0).toLowerCase(Locale.ROOT);
        if ("mvn".equals(executable) || "gradle".equals(executable) || "./gradlew".equals(executable)) {
            return 300;
        }
        if (Set.of("npm", "pnpm", "yarn", "bun").contains(executable)) {
            return 240;
        }
        if ("pytest".equals(executable) || "python".equals(executable) || "python3".equals(executable)) {
            return 180;
        }
        return 120;
    }

    public record ResolvedTestCommand(Path workingDirectory, List<String> command, String reason) {
        public ResolvedTestCommand(Path workingDirectory, List<String> command) {
            this(workingDirectory, command, "");
        }

        static ResolvedTestCommand unsupported(Path root) {
            return unsupported(root, "");
        }

        static ResolvedTestCommand unsupported(Path root, String reason) {
            return new ResolvedTestCommand(root, List.of(), reason == null ? "" : reason);
        }
    }
}