package com.labex.labexagent.diff;

public class PendingChange {
    private String id;
    private Integer studentId;
    private Integer projectId;
    private String conversationId;
    private Long taskId;
    private Long changeSetId;
    private String relativePath;
    private String changeType;
    private String beforeContent;
    private String afterContent;
    private String diff;
    private String status;

    public String getId() {
        return this.id;
    }

    public Integer getStudentId() {
        return this.studentId;
    }

    public Integer getProjectId() {
        return this.projectId;
    }

    public String getConversationId() {
        return this.conversationId;
    }

    public Long getTaskId() {
        return this.taskId;
    }

    public Long getChangeSetId() {
        return this.changeSetId;
    }

    public String getRelativePath() {
        return this.relativePath;
    }

    public String getChangeType() {
        return this.changeType;
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

    public String getStatus() {
        return this.status;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setStudentId(Integer studentId) {
        this.studentId = studentId;
    }

    public void setProjectId(Integer projectId) {
        this.projectId = projectId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public void setChangeSetId(Long changeSetId) {
        this.changeSetId = changeSetId;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
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

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof PendingChange)) {
            return false;
        }
        PendingChange other = (PendingChange)o;
        if (!other.canEqual(this)) {
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
        Long this$taskId = this.getTaskId();
        Long other$taskId = other.getTaskId();
        if (this$taskId == null ? other$taskId != null : !(this$taskId).equals(other$taskId)) {
            return false;
        }
        Long this$changeSetId = this.getChangeSetId();
        Long other$changeSetId = other.getChangeSetId();
        if (this$changeSetId == null ? other$changeSetId != null : !(this$changeSetId).equals(other$changeSetId)) {
            return false;
        }
        String this$id = this.getId();
        String other$id = other.getId();
        if (this$id == null ? other$id != null : !this$id.equals(other$id)) {
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
        return !(this$status == null ? other$status != null : !this$status.equals(other$status));
    }

    protected boolean canEqual(Object other) {
        return other instanceof PendingChange;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Integer $projectId = this.getProjectId();
        result = result * 59 + ($projectId == null ? 43 : ($projectId).hashCode());
        Long $taskId = this.getTaskId();
        result = result * 59 + ($taskId == null ? 43 : ($taskId).hashCode());
        Long $changeSetId = this.getChangeSetId();
        result = result * 59 + ($changeSetId == null ? 43 : ($changeSetId).hashCode());
        String $id = this.getId();
        result = result * 59 + ($id == null ? 43 : $id.hashCode());
        String $conversationId = this.getConversationId();
        result = result * 59 + ($conversationId == null ? 43 : $conversationId.hashCode());
        String $relativePath = this.getRelativePath();
        result = result * 59 + ($relativePath == null ? 43 : $relativePath.hashCode());
        String $changeType = this.getChangeType();
        result = result * 59 + ($changeType == null ? 43 : $changeType.hashCode());
        String $beforeContent = this.getBeforeContent();
        result = result * 59 + ($beforeContent == null ? 43 : $beforeContent.hashCode());
        String $afterContent = this.getAfterContent();
        result = result * 59 + ($afterContent == null ? 43 : $afterContent.hashCode());
        String $diff = this.getDiff();
        result = result * 59 + ($diff == null ? 43 : $diff.hashCode());
        String $status = this.getStatus();
        result = result * 59 + ($status == null ? 43 : $status.hashCode());
        return result;
    }

    public String toString() {
        return "PendingChange(id=" + this.getId() + ", studentId=" + this.getStudentId() + ", projectId=" + this.getProjectId() + ", conversationId=" + this.getConversationId() + ", taskId=" + this.getTaskId() + ", changeSetId=" + this.getChangeSetId() + ", relativePath=" + this.getRelativePath() + ", changeType=" + this.getChangeType() + ", beforeContent=" + this.getBeforeContent() + ", afterContent=" + this.getAfterContent() + ", diff=" + this.getDiff() + ", status=" + this.getStatus() + ")";
    }

    public PendingChange(String id, Integer studentId, Integer projectId, String conversationId, Long taskId, Long changeSetId, String relativePath, String changeType, String beforeContent, String afterContent, String diff, String status) {
        this.id = id;
        this.studentId = studentId;
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.changeSetId = changeSetId;
        this.relativePath = relativePath;
        this.changeType = changeType;
        this.beforeContent = beforeContent;
        this.afterContent = afterContent;
        this.diff = diff;
        this.status = status;
    }
}

