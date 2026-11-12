package com.labex.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectArchiveExtractor;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface StudentProjectService
extends IService<StudentProject> {
    /** 分片上传的导入入口：压缩包已在暂存目录，由 ProjectUploadSessionService 回调。 */
    public UploadResult importStagedArchive(Integer var1, Path var2, String var3, String var4,
                                            boolean var5, ProjectArchiveExtractor.ProgressListener var6);

    /** 上传结果：项目本体 + 本次解压的跳过统计。 */
    public record UploadResult(StudentProject project, int skippedCount, List<String> skippedSamples) {
    }

    public StudentProject createEmptyProject(Integer var1, String var2);

    public StudentProject createProject(Integer var1, String var2, String var3);

    public StudentProject getOwnedProject(Integer var1, Integer var2);

    public String readProjectFile(Integer var1, Integer var2, String var3);

    public Map<String, Object> readProjectFileForEditor(Integer var1, Integer var2, String var3);

    public StudentProject saveProjectFile(Integer var1, Integer var2, String var3, String var4);

    public StudentProject createProjectItem(Integer var1, Integer var2, String var3, String var4, String var5);

    public StudentProject deleteProjectItem(Integer var1, Integer var2, String var3);

    public StudentProject renameProjectItem(Integer var1, Integer var2, String var3, String var4);

    public StudentProject refreshProjectMetadata(Integer var1, Integer var2);

    public void refreshProjectMetadataAsync(Integer var1, Integer var2, String var3);

    public String askProjectAgent(Integer var1, Integer var2, String var3, String var4, String var5);

    public void deleteOwnedProject(Integer var1, Integer var2);

    public StudentProject renameProject(Integer var1, Integer var2, String var3);

    public List<Map<String, Object>> listProjectTree(Integer var1, Integer var2, String var3);

    public ProjectTreePage listProjectTreePage(Integer var1, Integer var2, String var3, int var4, int var5);

    public record ProjectTreePage(List<Map<String, Object>> entries, Integer nextOffset) {
    }

    public void exportProject(Integer var1, Integer var2, OutputStream var3);
}

