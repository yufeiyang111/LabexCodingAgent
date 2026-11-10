package com.labex.labexagent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 命令环境必须把 git 的仓库查找限制在工作区里。
 *
 * <p>没有 {@code GIT_CEILING_DIRECTORIES} 时，git 会从工作区一路向上找 {@code .git}，
 * 命中宿主机上任意外层仓库（用户把项目放在自己的仓库里、平台把工作区放在别的仓库下面）。
 * Agent 因此会报出与本项目无关的提交历史。
 */
class WorkerPolicyEnvironmentTest {

    private static final Path WORKSPACE = Path.of("D:/LabexAgent/workspaces/1/abc/workspace");

    @Test
    void gitCeilingIsTheWorkspaceParentSoDiscoveryStopsInsideTheWorkspace() {
        // 给工作区本身没用：当前目录总会被搜索，上一级仍会被找到。上界必须是父目录。
        assertEquals(WORKSPACE.getParent().toString(), WorkerPolicy.gitCeiling(WORKSPACE));
    }

    @Test
    void safeEnvironmentCarriesTheGitCeiling() {
        Map<String, String> environment = WorkerPolicy.defaults().safeEnvironment(WORKSPACE, Map.of());

        assertEquals(WORKSPACE.getParent().toString(), environment.get("GIT_CEILING_DIRECTORIES"));
    }

    @Test
    void safeEnvironmentStillAllowsOnlyTheHostAllowlist() {
        Map<String, String> environment = WorkerPolicy.defaults()
                .safeEnvironment(WORKSPACE, Map.of("PATH", "/usr/bin", "SECRET_TOKEN", "leak"));

        assertEquals("/usr/bin", environment.get("PATH"));
        assertFalse(environment.containsKey("SECRET_TOKEN"), "宿主机环境变量不得整体透传");
        assertTrue(environment.get("HOME").startsWith(WORKSPACE.toString()));
    }
}
