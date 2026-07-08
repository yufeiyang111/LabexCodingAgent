package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName(value="t_agent_verification")
public class AgentVerification {
    @TableId(value="verification_id", type=IdType.AUTO)
    private Long verificationId;
    @TableField(value="task_id")
    private Long taskId;
    @TableField(value="change_set_id")
    private Long changeSetId;
    @TableField(value="student_id")
    private Integer studentId;
    @TableField(value="project_id")
    private Integer projectId;
    @TableField(value="command")
    private String command;
    @TableField(value="status")
    private String status;
    @TableField(value="exit_code")
    private Integer exitCode;
    @TableField(value="output")
    private String output;
    @TableField(value="create_time")
    private LocalDateTime createTime;

    public Long getVerificationId() {
        return this.verificationId;
    }

    public Long getTaskId() {
        return this.taskId;
    }

    public Long getChangeSetId() {
        return this.changeSetId;
    }

    public Integer getStudentId() {
        return this.studentId;
    }

    public Integer getProjectId() {
        return this.projectId;
    }

    public String getCommand() {
        return this.command;
    }

    public String getStatus() {
        return this.status;
    }

    public Integer getExitCode() {
        return this.exitCode;
    }

    public String getOutput() {
        return this.output;
    }

    public LocalDateTime getCreateTime() {
        return this.createTime;
    }

    public void setVerificationId(Long verificationId) {
        this.verificationId = verificationId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public void setChangeSetId(Long changeSetId) {
        this.changeSetId = changeSetId;
    }

    public void setStudentId(Integer studentId) {
        this.studentId = studentId;
    }

    public void setProjectId(Integer projectId) {
        this.projectId = projectId;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof AgentVerification)) {
            return false;
        }
        AgentVerification other = (AgentVerification)o;
        if (!other.canEqual(this)) {
            return false;
        }
        Long this$verificationId = this.getVerificationId();
        Long other$verificationId = other.getVerificationId();
        if (this$verificationId == null ? other$verificationId != null : !(this$verificationId).equals(other$verificationId)) {
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
        Integer this$exitCode = this.getExitCode();
        Integer other$exitCode = other.getExitCode();
        if (this$exitCode == null ? other$exitCode != null : !(this$exitCode).equals(other$exitCode)) {
            return false;
        }
        String this$command = this.getCommand();
        String other$command = other.getCommand();
        if (this$command == null ? other$command != null : !this$command.equals(other$command)) {
            return false;
        }
        String this$status = this.getStatus();
        String other$status = other.getStatus();
        if (this$status == null ? other$status != null : !this$status.equals(other$status)) {
            return false;
        }
        String this$output = this.getOutput();
        String other$output = other.getOutput();
        if (this$output == null ? other$output != null : !this$output.equals(other$output)) {
            return false;
        }
        LocalDateTime this$createTime = this.getCreateTime();
        LocalDateTime other$createTime = other.getCreateTime();
        return !(this$createTime == null ? other$createTime != null : !(this$createTime).equals(other$createTime));
    }

    protected boolean canEqual(Object other) {
        return other instanceof AgentVerification;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        Long $verificationId = this.getVerificationId();
        result = result * 59 + ($verificationId == null ? 43 : ($verificationId).hashCode());
        Long $taskId = this.getTaskId();
        result = result * 59 + ($taskId == null ? 43 : ($taskId).hashCode());
        Long $changeSetId = this.getChangeSetId();
        result = result * 59 + ($changeSetId == null ? 43 : ($changeSetId).hashCode());
        Integer $studentId = this.getStudentId();
        result = result * 59 + ($studentId == null ? 43 : ($studentId).hashCode());
        Integer $projectId = this.getProjectId();
        result = result * 59 + ($projectId == null ? 43 : ($projectId).hashCode());
        Integer $exitCode = this.getExitCode();
        result = result * 59 + ($exitCode == null ? 43 : ($exitCode).hashCode());
        String $command = this.getCommand();
        result = result * 59 + ($command == null ? 43 : $command.hashCode());
        String $status = this.getStatus();
        result = result * 59 + ($status == null ? 43 : $status.hashCode());
        String $output = this.getOutput();
        result = result * 59 + ($output == null ? 43 : $output.hashCode());
        LocalDateTime $createTime = this.getCreateTime();
        result = result * 59 + ($createTime == null ? 43 : ($createTime).hashCode());
        return result;
    }

    public String toString() {
        return "AgentVerification(verificationId=" + this.getVerificationId() + ", taskId=" + this.getTaskId() + ", changeSetId=" + this.getChangeSetId() + ", studentId=" + this.getStudentId() + ", projectId=" + this.getProjectId() + ", command=" + this.getCommand() + ", status=" + this.getStatus() + ", exitCode=" + this.getExitCode() + ", output=" + this.getOutput() + ", createTime=" + String.valueOf(this.getCreateTime()) + ")";
    }
}

