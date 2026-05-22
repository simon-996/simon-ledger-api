package com.simon.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ledger_change_log")
public class LedgerChangeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String uuid;

    private Long ledgerId;

    private String entityType;

    private String entityUuid;

    private String operation;

    private Long operatorUserId;

    private Integer version;

    private LocalDateTime createdAt;
}
