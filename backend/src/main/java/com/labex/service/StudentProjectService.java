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

    public StudentProject createEmptyProject(Integer var1, String var2);

    public StudentProject createProject(Integer var1, String var2, String var3);

    public StudentProject getOwnedProject(Integer var1, Integer var2);

    public String readProjectFile(Integer var1, Integer var2, String var3);

    public StudentProject saveProjectFile(Integer var1, Integer var2, String var3, String var4);

    public StudentProject createProjectItem(Integer var1, Integer var2, String var3, String var4, String var5);

    public StudentProject deleteProjectItem(Integer var1, Integer var2, String var3);

    public StudentProject renameProjectItem(Integer var1, Integer var2, String var3, String var4);

    public StudentProject refreshProjectMetadata(Integer var1, Integer var2);

    public String askProjectAgent(Integer var1, Integer var2, String var3, String var4, String var5);

    public void deleteOwnedProject(Integer var1, Integer var2);

    public StudentProject renameProject(Integer var1, Integer var2, String var3);

    public List<Map<String, Object>> listProjectTree(Integer var1, Integer var2, String var3);

    public void exportProject(Integer var1, Integer var2, OutputStream var3);
}

