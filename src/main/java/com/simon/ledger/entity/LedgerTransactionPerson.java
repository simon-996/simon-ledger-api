package com.simon.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ledger_transaction_person")
public class LedgerTransactionPerson {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long transactionId;

    private Long personId;

    private BigDecimal shareAmount;

    private BigDecimal shareRatio;

    private LocalDateTime createdAt;
}
