package com.apigw.common.exception;

/**
 * 业务异常。需要向调用方返回明确失败原因时抛出，由全局异常处理成统一结构。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(1, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
