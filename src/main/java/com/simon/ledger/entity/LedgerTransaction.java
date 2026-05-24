package com.simon.ledger.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ledger_transaction")
public class LedgerTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String uuid;

    private Long ledgerId;

    private Integer type;

    private Long payerPersonId;

    private BigDecimal amount;

    private String currencyCode;

    private String category;

    private String note;

    private Long createdByUserId;

    private Long lastModifiedByUserId;

    private String clientOperationId;

    private Integer version;

    private LocalDateTime happenedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
