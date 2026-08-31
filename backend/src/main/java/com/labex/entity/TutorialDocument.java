package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_tutorial_document")
public class TutorialDocument {
    @TableId(type = IdType.AUTO)
    private Long documentId;
    private String slug;
    private String title;
    private String summary;
    private String category;
    private String contentMarkdown;
    private Integer sortOrder;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime publishedAt;
}
