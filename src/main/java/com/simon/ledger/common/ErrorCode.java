package com.simon.ledger.common;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(0, "ok"),
    BAD_REQUEST(400001, "参数错误"),
    UNAUTHORIZED(401001, "未登录"),
    FORBIDDEN(403001, "无权限"),
    NOT_FOUND(404001, "资源不存在"),
    CONFLICT(409001, "数据冲突"),
    SYSTEM_ERROR(500001, "系统错误");

    private final int code;

    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
