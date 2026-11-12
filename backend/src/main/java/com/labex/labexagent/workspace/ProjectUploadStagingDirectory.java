package com.labex.labexagent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 上传暂存根目录的唯一来源：分片写入与待导入压缩包都落在这里，导入结束即删除。
 *
 * <p>与项目工作区同卷（默认在 {@code project-base-path} 下），组装与解压才不会跨卷复制。
 * 单独成类是因为有两处要用：分片会话服务（写分片）与 {@code StudentProjectServiceImpl}
 * （把 multipart 上传的压缩包落到磁盘）。放在任一处都会让另一处去依赖它的实现细节。
 */
@Component
public class ProjectUploadStagingDirectory {

    private final Path root;

    public ProjectUploadStagingDirectory(ProjectArchiveLimits limits,
                                         @Value("${labex-agent.project-base-path:./student_projects}") String projectBasePath) {
        String configured = limits.getStagingDirectory();
        this.root = (configured == null || configured.isBlank()
                ? Path.of(projectBasePath, ".upload-staging")
                : Path.of(configured)).toAbsolutePath().normalize();
    }

    public Path root() throws IOException {
        Files.createDirectories(root);
        return root;
    }
}
