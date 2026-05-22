package com.simon.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("idempotency_record")
public class IdempotencyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String requestKey;

    private String requestMethod;

    private String requestPath;

    private Integer responseCode;

    private String responseBody;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;
}
