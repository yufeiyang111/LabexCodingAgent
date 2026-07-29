package com.labex.labexagent.commandsecurity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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
        VerificationStrategy selected = strategy == null ? VerificationStrategy.AUTO : strategy;
        ResolvedTestCommand primary = resolveProjectInternal(workspaceRoot, selected);
        if (!primary.command().isEmpty() || fallbackStrategy == null || fallbackStrategy == selected
                || fallbackStrategy == VerificationStrategy.MANUAL) {
            return primary;
        }
        return resolveProjectInternal(workspaceRoot, fallbackStrategy);
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

    private static List<String> commandFor(Path projectRoot, VerificationStrategy strategy) {
        if (strategy == VerificationStrategy.MANUAL) return List.of();
        if (Files.exists(projectRoot.resolve("pom.xml"), new LinkOption[0])) {
            return switch (strategy) {
                case COMPILE -> List.of("mvn", "compile");
                case BUILD -> List.of("mvn", "package", "-DskipTests");
                case OFFLINE_TEST -> List.of("mvn", "-o", "test");
                case TEST, AUTO -> List.of("mvn", "test");
                case MANUAL -> List.of();
            };
        }
        if (Files.exists(projectRoot.resolve("package.json"), new LinkOption[0])) {
            return npmVerificationCommand(projectRoot.resolve("package.json"), strategy);
        }
        if (Files.exists(projectRoot.resolve("build.gradle"), new LinkOption[0])
                || Files.exists(projectRoot.resolve("build.gradle.kts"), new LinkOption[0])) {
            return strategy == VerificationStrategy.COMPILE ? List.of("./gradlew", "classes") : List.of("./gradlew", "test");
        }
        if (Files.exists(projectRoot.resolve("requirements.txt"), new LinkOption[0])
                || Files.exists(projectRoot.resolve("pyproject.toml"), new LinkOption[0])) {
            return strategy == VerificationStrategy.COMPILE ? List.of("python", "-m", "compileall", ".") : List.of("python", "-m", "pytest");
        }
        if (Files.exists(projectRoot.resolve("pytest.ini"), new LinkOption[0])) return List.of("pytest");
        return List.of();
    }

    public record ResolvedTestCommand(Path workingDirectory, List<String> command) {
        static ResolvedTestCommand unsupported(Path root) { return new ResolvedTestCommand(root, List.of()); }
    }
}