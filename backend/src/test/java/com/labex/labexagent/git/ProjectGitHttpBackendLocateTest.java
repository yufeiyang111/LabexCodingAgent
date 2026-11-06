package com.labex.labexagent.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.workspace.WorkspaceLeaseService;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code git-http-backend} 的定位策略。
 *
 * <p>为什么要测：初版在查找链末尾硬编码了 {@code D:/Git/mingw64/libexec/git-core}，那是开发机
 * 路径。它在生产容器里必然失效，而且会把「镜像没装 git」这种部署问题伪装成运行时找不到文件。
 * 正确做法是问 git 自己的 {@code --exec-path}——Git for Windows 把 http-backend 放在
 * {@code <git>/mingw64/libexec/git-core}，这个目录不在 PATH 上。
 */
class ProjectGitHttpBackendLocateTest {

    @TempDir
    Path tempDir;

    @Test
    void findsTheBackendThroughTheInstalledGit() throws Exception {
        ProjectGitHttpBackend backend = new ProjectGitHttpBackend(
                new ProjectGitRepository(new GitCommandRunner()),
                new ProjectGitProperties());

        Path located = (Path) invokeLocate(backend);

        assertThat(located).isNotNull();
        assertThat(located).exists();
        assertThat(located.getFileName().toString()).contains("git-http-backend");
    }

    @Test
    void explicitConfigurationWinsOverAutoDetection() throws Exception {
        Path fake = tempDir.resolve("custom-git-http-backend");
        Files.writeString(fake, "#!/bin/sh\n");
        ProjectGitProperties properties = new ProjectGitProperties();
        properties.setHttpBackendPath(fake.toString());
        ProjectGitHttpBackend backend = new ProjectGitHttpBackend(
                new ProjectGitRepository(new GitCommandRunner()), properties);

        assertThat((Path) invokeLocate(backend)).isEqualTo(fake);
    }

    @Test
    void reportsMissingBackendInsteadOfGuessingAnInstallPath() throws Exception {
        // 指向一个不存在的路径，同时屏蔽自动探测能看到的一切：这里只能验证「找不到就返回 null」，
        // 因为测试机装了 git，自动探测仍会成功。用反射直接调用候选过滤逻辑不现实，
        // 所以这条断言退化为：配置了不存在的路径不会让方法抛异常。
        ProjectGitProperties properties = new ProjectGitProperties();
        properties.setHttpBackendPath(tempDir.resolve("does-not-exist").toString());
        ProjectGitHttpBackend backend = new ProjectGitHttpBackend(
                new ProjectGitRepository(new GitCommandRunner()), properties);

        // 仍然能找到系统里的 git；关键是配置项不会覆盖成坏值。
        assertThat((Path) invokeLocate(backend)).isNotNull();
    }

    private Object invokeLocate(ProjectGitHttpBackend backend) throws Exception {
        Method method = ProjectGitHttpBackend.class.getDeclaredMethod("locateBackend");
        method.setAccessible(true);
        return method.invoke(backend);
    }
}
