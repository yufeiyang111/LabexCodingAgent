package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value="t_student_project")
public class StudentProject {
    @TableId(value="project_id", type=IdType.AUTO)
    private Integer projectId;
    @TableField(value="student_id")
    private Integer studentId;
    @TableField(value="project_name")
    private String projectName;
    @TableField(value="original_file_name")
    private String originalFileName;
    @TableField(value="archive_path")
    private String archivePath;
    @TableField(value="workspace_path")
    private String workspacePath;
    @TableField(value="structure_json")
    private String structureJson;
    @TableField(value="file_count")
    private Integer fileCount;
    @TableField(value="total_size")
    private Long totalSize;
    @TableField(value="status")
    private Integer status;
    @TableField(value="create_time")
    private LocalDateTime createTime;
    @TableField(value="update_time")
    private LocalDateTime updateTime;

    public Integer getProjectId() {
        return this.projectId;
    }

    public Integer getStudentId() {
        return this.studentId;
    }

    public String getProjectName() {
        return this.projectName;
    }

    public String getOriginalFileName() {
        return this.originalFileName;
    }

    public String getArchivePath() {
        return this.archivePath;
    }

    public String getWorkspacePath() {
        return this.workspacePath;
    }

    public String getStructureJson() {
        return this.structureJson;
    }

    public Integer getFileCount() {
        return this.fileCount;
    }

    public Long getTotalSize() {
        return this.totalSize;
    }

    public Integer getStatus() {
        return this.status;
    }

    public LocalDateTime getCreateTime() {
        return this.createTime;
    }

    public LocalDateTime getUpdateTime() {
        return this.updateTime;
    }

    public void setProjectId(Integer projectId) {
        this.projectId = projectId;
    }

    public void setStudentId(Integer studentId) {
        this.studentId = studentId;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public void setArchivePath(String archivePath) {
        this.archivePath = archivePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public void setStructureJson(String structureJson) {
        this.structureJson = structureJson;
    }

    public void setFileCount(Integer fileCount) {
        this.fileCount = fileCount;
    }

    public void setTotalSize(Long totalSize) {
        this.totalSize = totalSize;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof StudentProject)) {
            return false;
        }
        StudentProject other = (StudentProject)o;
        if (!other.canEqual(this)) {
            return false;
        }
        Integer this$projectId = this.getProjectId();
        Integer other$projectId = other.getProjectId();
        if (this$projectId == null ? other$projectId != null : !(this$projectId).equals(other$projectId)) {
            return false;
        }
        Integer this$studentId = this.getStudentId();
        Integer other$studentId = other.getStudentId();
        if (this$studentId == null ? other$studentId != null : !(this$studentId).equals(other$studentId)) {
            return false;
        }
        Integer this$fileCount = this.getFileCount();
        Integer other$fileCount = other.getFileCount();
        if (this$fileCount == null ? other$fileCount != null : !(this$fileCount).equals(other$fileCount)) {
            return false;
        }
        Long this$totalSize = this.getTotalSize();
        Long other$totalSize = other.getTotalSize();
        if (this$totalSize == null ? other$totalSize != null : !(this$totalSize).equals(other$totalSize)) {
            return false;
        }
        Integer this$status = this.getStatus();
        Integer other$status = other.getStatus();
        if (this$status == null ? other$status != null : !(this$status).equals(other$status)) {
            return false;
        }
        String this$projectName = this.getProjectName();
        String other$projectName = other.getProjectName();
        if (this$projectName == null ? other$projectName != null : !this$projectName.equals(other$projectName)) {
            return false;
        }
        String this$originalFileName = this.getOriginalFileName();
        String other$originalFileName = other.getOriginalFileName();
        if (this$originalFileName == null ? other$originalFileName != null : !this$originalFileName.equals(other$originalFileName)) {
            return false;
        }
        String this$archivePath = this.getArchivePath();
        String other$archivePath = other.getArchivePath();
        if (this$archivePath == null ? other$archivePath != null : !this$archivePath.equals(other$archivePath)) {
            return false;
        }
        String this$workspacePath = this.getWorkspacePath();
        String other$workspacePath = other.getWorkspacePath();
        if (this$workspacePath == null ? other$workspacePath != null : !this$workspacePath.equals(other$workspacePath)) {
            return false;
        }
        String this$structureJson = this.getStructureJson();
        String other$structureJson = other.getStructureJson();
        if (this$structureJson == null ? other$structureJson != null : !this$structureJson.equals(other$structureJson)) {
            return false;
        }
        LocalDateTime this$createTime = this.getCreateTime();
        LocalDateTime other$createTime = other.getCreateTime();
        if (this$createTime == null ? other$createTime != null : !(this$createTime).equals(other$createTime)) {
            return false;
        }
        LocalDateTime this$updateTime = this.getUpdateTime();
        LocalDateTime other$updateTime = other.getUpdateTime();
        return !(this$updateTime == null ? other$updateTime != null : !(this$updateTime).equals(other$updateTime));
    }

    protected boolean canEqual(Object other) {
        return other instanceof StudentProject;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Integer $projectId = this.getProjectId();
        result = result * 59 + ($projectId == null ? 43 : ($projectId).hashCode());
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Integer $fileCount = this.getFileCount();
        result = result * 59 + ($fileCount == null ? 43 : ($fileCount).hashCode());
        Long $totalSize = this.getTotalSize();
        result = result * 59 + ($totalSize == null ? 43 : ($totalSize).hashCode());
        Integer $status = this.getStatus();
        result = result * 59 + ($status == null ? 43 : ($status).hashCode());
        String $projectName = this.getProjectName();
        result = result * 59 + ($projectName == null ? 43 : $projectName.hashCode());
        String $originalFileName = this.getOriginalFileName();
        result = result * 59 + ($originalFileName == null ? 43 : $originalFileName.hashCode());
        String $archivePath = this.getArchivePath();
        result = result * 59 + ($archivePath == null ? 43 : $archivePath.hashCode());
        String $workspacePath = this.getWorkspacePath();
        result = result * 59 + ($workspacePath == null ? 43 : $workspacePath.hashCode());
        String $structureJson = this.getStructureJson();
        result = result * 59 + ($structureJson == null ? 43 : $structureJson.hashCode());
        LocalDateTime $createTime = this.getCreateTime();
        result = result * 59 + ($createTime == null ? 43 : ($createTime).hashCode());
        LocalDateTime $updateTime = this.getUpdateTime();
        result = result * 59 + ($updateTime == null ? 43 : ($updateTime).hashCode());
        return result;
    }

    public String toString() {
        return "StudentProject(projectId=" + this.getProjectId() + ", studentId=" + this.getStudentId() + ", projectName=" + this.getProjectName() + ", originalFileName=" + this.getOriginalFileName() + ", archivePath=" + this.getArchivePath() + ", workspacePath=" + this.getWorkspacePath() + ", structureJson=" + this.getStructureJson() + ", fileCount=" + this.getFileCount() + ", totalSize=" + this.getTotalSize() + ", status=" + this.getStatus() + ", createTime=" + String.valueOf(this.getCreateTime()) + ", updateTime=" + String.valueOf(this.getUpdateTime()) + ")";
    }
}

