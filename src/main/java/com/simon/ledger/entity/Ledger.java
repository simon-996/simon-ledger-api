package com.simon.ledger.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import com.simon.ledger.config.mybatis.CurrencyRatesTypeHandler;
import java.time.LocalDateTime;

@Data
@TableName(value = "ledger", autoResultMap = true)
public class Ledger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String uuid;

    private String name;

    private String baseCurrencyCode;

    private BigDecimal exchangeRateToCny;

    @TableField(typeHandler = CurrencyRatesTypeHandler.class)
    private Map<String, BigDecimal> currencyRatesToCny;

    private Long ownerUserId;

    private Integer version = 1;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
