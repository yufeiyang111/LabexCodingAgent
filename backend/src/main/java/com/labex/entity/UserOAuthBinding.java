package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_user_oauth_binding")
public class UserOAuthBinding {
    @TableId(value = "binding_id", type = IdType.AUTO)
    private Integer bindingId;

    @TableField("user_id")
    private Integer userId;

    @TableField("provider")
    private String provider;

    @TableField("subject")
    private String subject;

    @TableField("provider_email")
    private String providerEmail;

    @TableField("display_name")
    private String displayName;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
