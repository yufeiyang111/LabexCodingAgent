package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.StudentProject;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StudentProjectMapper extends BaseMapper<StudentProject> {
    @Select("SELECT * FROM t_student_project WHERE status = 1 "
            + "AND workspace_path IS NOT NULL AND workspace_path <> '' ORDER BY project_id")
    List<StudentProject> selectActiveProjects();
}
