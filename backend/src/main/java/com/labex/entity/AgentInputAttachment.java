package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Agent 输入图片附件；只保存文件元数据，图片二进制不写入 transcript。 */
@TableName("t_agent_input_attachment")
public class AgentInputAttachment {
    @TableId(value = "attachment_id", type = IdType.INPUT)
    private String attachmentId;
    @TableField("student_id") private Integer studentId;
    @TableField("project_id") private Integer projectId;
    @TableField("task_id") private Long taskId;
    @TableField("conversation_id") private String conversationId;
    @TableField("original_filename") private String originalFilename;
    @TableField("mime_type") private String mimeType;
    @TableField("size_bytes") private Long sizeBytes;
    @TableField("sha256") private String sha256;
    @TableField("storage_key") private String storageKey;
    @TableField("status") private String status;
    @TableField("expires_at") private LocalDateTime expiresAt;
    @TableField("create_time") private LocalDateTime createTime;
    @TableField("update_time") private LocalDateTime updateTime;
    public String getAttachmentId(){return attachmentId;} public void setAttachmentId(String v){attachmentId=v;}
    public Integer getStudentId(){return studentId;} public void setStudentId(Integer v){studentId=v;}
    public Integer getProjectId(){return projectId;} public void setProjectId(Integer v){projectId=v;}
    public Long getTaskId(){return taskId;} public void setTaskId(Long v){taskId=v;}
    public String getConversationId(){return conversationId;} public void setConversationId(String v){conversationId=v;}
    public String getOriginalFilename(){return originalFilename;} public void setOriginalFilename(String v){originalFilename=v;}
    public String getMimeType(){return mimeType;} public void setMimeType(String v){mimeType=v;}
    public Long getSizeBytes(){return sizeBytes;} public void setSizeBytes(Long v){sizeBytes=v;}
    public String getSha256(){return sha256;} public void setSha256(String v){sha256=v;}
    public String getStorageKey(){return storageKey;} public void setStorageKey(String v){storageKey=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getExpiresAt(){return expiresAt;} public void setExpiresAt(LocalDateTime v){expiresAt=v;}
    public LocalDateTime getCreateTime(){return createTime;} public void setCreateTime(LocalDateTime v){createTime=v;}
    public LocalDateTime getUpdateTime(){return updateTime;} public void setUpdateTime(LocalDateTime v){updateTime=v;}
}
