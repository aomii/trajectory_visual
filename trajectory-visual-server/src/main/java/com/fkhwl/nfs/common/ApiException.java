package com.fkhwl.nfs.common;

/**
 * 业务异常：抛到统一异常处理器转 Result。
 */
public class ApiException extends RuntimeException {

    private final int code;

    public ApiException(String message) {
        super(message);
        this.code = 500;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }

    public ApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
