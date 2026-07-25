package com.labex.labexagent.commandsecurity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves a fixed, direct-argv test command from workspace markers. It deliberately exposes no
 * caller-provided command string or shell syntax.
 */
public final class TestCommandResolver {
    private TestCommandResolver() {
    }

    public static List<String> resolve(Path workspaceRoot) {
        return resolveProject(workspaceRoot).command();
    }

    public static ResolvedTestCommand resolveProject(Path workspaceRoot) {
        if (workspaceRoot == null) {
            return ResolvedTestCommand.unsupported(null);
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        List<String> rootCommand = commandFor(root);
        if (!rootCommand.isEmpty()) {
            return new ResolvedTestCommand(root, rootCommand);
        }
        try (var children = Files.list(root)) {
            return children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> new ResolvedTestCommand(path, commandFor(path)))
                    .filter(resolved -> !resolved.command().isEmpty())
                    .findFirst()
                    .orElse(ResolvedTestCommand.unsupported(root));
        } catch (IOException ignored) {
            return ResolvedTestCommand.unsupported(root);
        }
    }

    public static String canonicalCommand(Path workspaceRoot) {
        return String.join(" ", resolve(workspaceRoot));
    }

    private static List<String> commandFor(Path projectRoot) {
        if (Files.exists(projectRoot.resolve("pom.xml"), new LinkOption[0])) {
            return List.of("mvn", "test");
        }
        if (Files.exists(projectRoot.resolve("package.json"), new LinkOption[0])) {
            return List.of("npm", "test");
        }
        if (Files.exists(projectRoot.resolve("build.gradle"), new LinkOption[0])
                || Files.exists(projectRoot.resolve("build.gradle.kts"), new LinkOption[0])) {
            return List.of("./gradlew", "test");
        }
        if (Files.exists(projectRoot.resolve("requirements.txt"), new LinkOption[0])
                || Files.exists(projectRoot.resolve("pyproject.toml"), new LinkOption[0])) {
            return List.of("python", "-m", "pytest");
        }
        if (Files.exists(projectRoot.resolve("pytest.ini"), new LinkOption[0])) {
            return List.of("pytest");
        }
        return List.of();
    }

    public record ResolvedTestCommand(Path workingDirectory, List<String> command) {
        static ResolvedTestCommand unsupported(Path root) {
            return new ResolvedTestCommand(root, List.of());
        }
    }
}
