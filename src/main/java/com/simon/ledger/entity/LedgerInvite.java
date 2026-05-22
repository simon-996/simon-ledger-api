package com.simon.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ledger_invite")
public class LedgerInvite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String uuid;

    private Long ledgerId;

    private String code;

    private String role;

    private Long createdByUserId;

    private Integer maxUses;

    private Integer usedCount;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime disabledAt;
}
