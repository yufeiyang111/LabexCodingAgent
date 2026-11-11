package com.labex.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.labex.entity.StudentProject;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

public interface StudentProjectService
extends IService<StudentProject> {
    public StudentProject uploadProject(Integer var1, MultipartFile var2, String var3);

    /** 上传项目压缩包；{@code skipDependencyDirectories} 为 true 时解压阶段跳过依赖与构建产物目录。 */
    public StudentProject uploadProject(Integer var1, MultipartFile var2, String var3, boolean var4);

    /** 与四参版本相同，额外返回解压统计（跳过数量与样本），用于上传结果展示。 */
    public UploadResult uploadProjectWithStats(Integer var1, MultipartFile var2, String var3, boolean var4);

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

