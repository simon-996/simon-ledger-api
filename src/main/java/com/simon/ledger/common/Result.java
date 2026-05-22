package com.simon.ledger.common;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Result<T> {

    private Integer code;

    private String message;

    private T data;

    public static <T> Result<T> ok() {
        return new Result<T>().setCode(ErrorCode.SUCCESS.getCode()).setMessage("ok");
    }

    public static <T> Result<T> ok(T data) {
        return new Result<T>().setCode(ErrorCode.SUCCESS.getCode()).setMessage("ok").setData(data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<T>().setCode(errorCode.getCode()).setMessage(errorCode.getMessage());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<T>().setCode(errorCode.getCode()).setMessage(message);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<T>().setCode(code).setMessage(message);
    }
}
