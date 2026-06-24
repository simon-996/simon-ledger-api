package com.simon.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("admin_operation_log")
public class AdminOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String uuid;

    private Long adminUserId;

    private String action;

    private String targetType;

    private String targetUuid;

    private String detail;

    private String ipAddress;

    private LocalDateTime createdAt;
}
