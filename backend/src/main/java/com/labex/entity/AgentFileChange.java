package com.labex.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value="t_agent_file_change")
public class AgentFileChange {
    @TableId(value="change_id")
    private String changeId;
    @TableField(value="change_set_id")
    private Long changeSetId;
    @TableField(value="task_id")
    private Long taskId;
    @TableField(value="conversation_id")
    private String conversationId;
    @TableField(value="student_id")
    private Integer studentId;
    @TableField(value="project_id")
    private Integer projectId;
    @TableField(value="relative_path")
    private String relativePath;
    @TableField(value="change_type")
    private String changeType;
    @TableField(value="before_hash")
    private String beforeHash;
    @TableField(value="before_content")
    private String beforeContent;
    @TableField(value="after_content")
    private String afterContent;
    @TableField(value="diff_text")
    private String diff;
    @TableField(value="snapshot_before_ref")
    private String snapshotBeforeRef;
    @TableField(value="snapshot_after_ref")
    private String snapshotAfterRef;
    @TableField(value="snapshot_paths")
    private String snapshotPaths;
    @TableField(value="snapshot_status")
    private String snapshotStatus;
    @TableField(value="status")
    private String status;
    @TableField(value="create_time")
    private LocalDateTime createTime;
    @TableField(value="update_time")
    private LocalDateTime updateTime;
    @TableField(value="applied_time")
    private LocalDateTime appliedTime;
    @TableField(value="rejected_time")
    private LocalDateTime rejectedTime;
    @TableField(value="undone_time")
    private LocalDateTime undoneTime;

    public String getChangeId() {
        return this.changeId;
    }

    public Long getChangeSetId() {
        return this.changeSetId;
    }

    public Long getTaskId() {
        return this.taskId;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public Integer getStudentId() {
        return this.studentId;
    }

    public Integer getProjectId() {
        return this.projectId;
    }

    public String getRelativePath() {
        return this.relativePath;
    }

    public String getChangeType() {
        return this.changeType;
    }

    public String getBeforeHash() {
        return this.beforeHash;
    }

    public String getBeforeContent() {
        return this.beforeContent;
    }

    public String getAfterContent() {
        return this.afterContent;
    }

    public String getDiff() {
        return this.diff;
    }

    public String getSnapshotBeforeRef() {
        return this.snapshotBeforeRef;
    }

    public String getSnapshotAfterRef() {
        return this.snapshotAfterRef;
    }

    public String getSnapshotPaths() {
        return this.snapshotPaths;
    }

    public String getSnapshotStatus() {
        return this.snapshotStatus;
    }

    public String getStatus() {
        return this.status;
    }

    public LocalDateTime getCreateTime() {
        return this.createTime;
    }

    public LocalDateTime getUpdateTime() {
        return this.updateTime;
    }

    public LocalDateTime getAppliedTime() {
        return this.appliedTime;
    }

    public LocalDateTime getRejectedTime() {
        return this.rejectedTime;
    }

    public LocalDateTime getUndoneTime() {
        return this.undoneTime;
    }

    public void setChangeId(String changeId) {
        this.changeId = changeId;
    }

    public void setChangeSetId(Long changeSetId) {
        this.changeSetId = changeSetId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void setStudentId(Integer studentId) {
        this.studentId = studentId;
    }

    public void setProjectId(Integer projectId) {
        this.projectId = projectId;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public void setBeforeHash(String beforeHash) {
        this.beforeHash = beforeHash;
    }

    public void setBeforeContent(String beforeContent) {
        this.beforeContent = beforeContent;
    }

    public void setAfterContent(String afterContent) {
        this.afterContent = afterContent;
    }

    public void setDiff(String diff) {
        this.diff = diff;
    }

    public void setSnapshotBeforeRef(String snapshotBeforeRef) {
        this.snapshotBeforeRef = snapshotBeforeRef;
    }

    public void setSnapshotAfterRef(String snapshotAfterRef) {
        this.snapshotAfterRef = snapshotAfterRef;
    }

    public void setSnapshotPaths(String snapshotPaths) {
        this.snapshotPaths = snapshotPaths;
    }

    public void setSnapshotStatus(String snapshotStatus) {
        this.snapshotStatus = snapshotStatus;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public void setAppliedTime(LocalDateTime appliedTime) {
        this.appliedTime = appliedTime;
    }

    public void setRejectedTime(LocalDateTime rejectedTime) {
        this.rejectedTime = rejectedTime;
    }

    public void setUndoneTime(LocalDateTime undoneTime) {
        this.undoneTime = undoneTime;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AgentFileChange)) {
            return false;
        }
        AgentFileChange other = (AgentFileChange)o;
        if (!other.canEqual(this)) {
            return false;
        }
        Long this$changeSetId = this.getChangeSetId();
        Long other$changeSetId = other.getChangeSetId();
        if (this$changeSetId == null ? other$changeSetId != null : !(this$changeSetId).equals(other$changeSetId)) {
            return false;
        }
        Long this$taskId = this.getTaskId();
        Long other$taskId = other.getTaskId();
        if (this$taskId == null ? other$taskId != null : !(this$taskId).equals(other$taskId)) {
            return false;
        }
        Integer this$studentId = this.getStudentId();
        Integer other$studentId = other.getStudentId();
        if (this$studentId == null ? other$studentId != null : !(this$studentId).equals(other$studentId)) {
            return false;
        }
        Integer this$projectId = this.getProjectId();
        Integer other$projectId = other.getProjectId();
        if (this$projectId == null ? other$projectId != null : !(this$projectId).equals(other$projectId)) {
            return false;
        }
        String this$changeId = this.getChangeId();
        String other$changeId = other.getChangeId();
        if (this$changeId == null ? other$changeId != null : !this$changeId.equals(other$changeId)) {
            return false;
        }
        String this$conversationId = this.getConversationId();
        String other$conversationId = other.getConversationId();
        if (this$conversationId == null ? other$conversationId != null : !this$conversationId.equals(other$conversationId)) {
            return false;
        }
        String this$relativePath = this.getRelativePath();
        String other$relativePath = other.getRelativePath();
        if (this$relativePath == null ? other$relativePath != null : !this$relativePath.equals(other$relativePath)) {
            return false;
        }
        String this$changeType = this.getChangeType();
        String other$changeType = other.getChangeType();
        if (this$changeType == null ? other$changeType != null : !this$changeType.equals(other$changeType)) {
            return false;
        }
        String this$beforeHash = this.getBeforeHash();
        String other$beforeHash = other.getBeforeHash();
        if (this$beforeHash == null ? other$beforeHash != null : !this$beforeHash.equals(other$beforeHash)) {
            return false;
        }
        String this$beforeContent = this.getBeforeContent();
        String other$beforeContent = other.getBeforeContent();
        if (this$beforeContent == null ? other$beforeContent != null : !this$beforeContent.equals(other$beforeContent)) {
            return false;
        }
        String this$afterContent = this.getAfterContent();
        String other$afterContent = other.getAfterContent();
        if (this$afterContent == null ? other$afterContent != null : !this$afterContent.equals(other$afterContent)) {
            return false;
        }
        String this$diff = this.getDiff();
        String other$diff = other.getDiff();
        if (this$diff == null ? other$diff != null : !this$diff.equals(other$diff)) {
            return false;
        }
        String this$status = this.getStatus();
        String other$status = other.getStatus();
        if (this$status == null ? other$status != null : !this$status.equals(other$status)) {
            return false;
        }
        LocalDateTime this$createTime = this.getCreateTime();
        LocalDateTime other$createTime = other.getCreateTime();
        if (this$createTime == null ? other$createTime != null : !(this$createTime).equals(other$createTime)) {
            return false;
        }
        LocalDateTime this$updateTime = this.getUpdateTime();
        LocalDateTime other$updateTime = other.getUpdateTime();
        if (this$updateTime == null ? other$updateTime != null : !(this$updateTime).equals(other$updateTime)) {
            return false;
        }
        LocalDateTime this$appliedTime = this.getAppliedTime();
        LocalDateTime other$appliedTime = other.getAppliedTime();
        if (this$appliedTime == null ? other$appliedTime != null : !(this$appliedTime).equals(other$appliedTime)) {
            return false;
        }
        LocalDateTime this$rejectedTime = this.getRejectedTime();
        LocalDateTime other$rejectedTime = other.getRejectedTime();
        if (this$rejectedTime == null ? other$rejectedTime != null : !(this$rejectedTime).equals(other$rejectedTime)) {
            return false;
        }
        LocalDateTime this$undoneTime = this.getUndoneTime();
        LocalDateTime other$undoneTime = other.getUndoneTime();
        return !(this$undoneTime == null ? other$undoneTime != null : !(this$undoneTime).equals(other$undoneTime));
    }

    protected boolean canEqual(Object other) {
        return other instanceof AgentFileChange;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Long $changeSetId = this.getChangeSetId();
        result = result * 59 + ($changeSetId == null ? 43 : ($changeSetId).hashCode());
        Long $taskId = this.getTaskId();
        result = result * 59 + ($taskId == null ? 43 : ($taskId).hashCode());
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Integer $projectId = this.getProjectId();
        result = result * 59 + ($projectId == null ? 43 : ($projectId).hashCode());
        String $changeId = this.getChangeId();
        result = result * 59 + ($changeId == null ? 43 : $changeId.hashCode());
        String $conversationId = this.getConversationId();
        result = result * 59 + ($conversationId == null ? 43 : $conversationId.hashCode());
        String $relativePath = this.getRelativePath();
        result = result * 59 + ($relativePath == null ? 43 : $relativePath.hashCode());
        String $changeType = this.getChangeType();
        result = result * 59 + ($changeType == null ? 43 : $changeType.hashCode());
        String $beforeHash = this.getBeforeHash();
        result = result * 59 + ($beforeHash == null ? 43 : $beforeHash.hashCode());
        String $beforeContent = this.getBeforeContent();
        result = result * 59 + ($beforeContent == null ? 43 : $beforeContent.hashCode());
        String $afterContent = this.getAfterContent();
        result = result * 59 + ($afterContent == null ? 43 : $afterContent.hashCode());
        String $diff = this.getDiff();
        result = result * 59 + ($diff == null ? 43 : $diff.hashCode());
        String $status = this.getStatus();
        result = result * 59 + ($status == null ? 43 : $status.hashCode());
        LocalDateTime $createTime = this.getCreateTime();
        result = result * 59 + ($createTime == null ? 43 : ($createTime).hashCode());
        LocalDateTime $updateTime = this.getUpdateTime();
        result = result * 59 + ($updateTime == null ? 43 : ($updateTime).hashCode());
        LocalDateTime $appliedTime = this.getAppliedTime();
        result = result * 59 + ($appliedTime == null ? 43 : ($appliedTime).hashCode());
        LocalDateTime $rejectedTime = this.getRejectedTime();
        result = result * 59 + ($rejectedTime == null ? 43 : ($rejectedTime).hashCode());
        LocalDateTime $undoneTime = this.getUndoneTime();
        result = result * 59 + ($undoneTime == null ? 43 : ($undoneTime).hashCode());
        return result;
    }

    public String toString() {
        return "AgentFileChange(changeId=" + this.getChangeId() + ", changeSetId=" + this.getChangeSetId() + ", taskId=" + this.getTaskId() + ", conversationId=" + this.getConversationId() + ", studentId=" + this.getStudentId() + ", projectId=" + this.getProjectId() + ", relativePath=" + this.getRelativePath() + ", changeType=" + this.getChangeType() + ", beforeHash=" + this.getBeforeHash() + ", beforeContent=" + this.getBeforeContent() + ", afterContent=" + this.getAfterContent() + ", diff=" + this.getDiff() + ", status=" + this.getStatus() + ", createTime=" + String.valueOf(this.getCreateTime()) + ", updateTime=" + String.valueOf(this.getUpdateTime()) + ", appliedTime=" + String.valueOf(this.getAppliedTime()) + ", rejectedTime=" + String.valueOf(this.getRejectedTime()) + ", undoneTime=" + String.valueOf(this.getUndoneTime()) + ")";
    }
}
