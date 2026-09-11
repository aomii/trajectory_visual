package com.fkhwl.nfs.common;

import lombok.Getter;

/**
 * 统一响应包装（风格对齐老项目 com.fkhwl.starter.basic.Result，但为本系统自实现）。
 * 约定：code=200 成功；非 200 为失败，message 为失败原因；前端以 code===200 判成功。
 */
@Getter
public class Result<T> {
    private final int code;
    private final String message;
    private final T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    public static Result<Void> ok() {
        return new Result<>(200, "success", null);
    }

    public static <T> Result<T> failed(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> failed(String message) {
        return new Result<>(500, message, null);
    }

    public static <T> Result<T> notAvailable(String message) {
        // 算法/数据暂不可用：返回专用码 404，前端据此展示 not_available 状态而非伪造数字
        return new Result<>(404, message, null);
    }
}
