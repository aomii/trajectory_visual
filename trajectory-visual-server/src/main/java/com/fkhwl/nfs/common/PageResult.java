package com.fkhwl.nfs.common;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 通用分页结果（MyBatis-Plus IPage 的轻量出参，避免把 MP 类型暴露给前端）。
 */
@Getter
public class PageResult<T> {
    private final long total;
    private final long current;
    private final long size;
    private final List<T> records;

    public PageResult(long total, long current, long size, List<T> records) {
        this.total = total;
        this.current = current;
        this.size = size;
        this.records = records == null ? Collections.emptyList() : records;
    }
}
